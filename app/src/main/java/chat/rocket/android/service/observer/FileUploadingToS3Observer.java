package chat.rocket.android.service.observer;

import android.content.Context;
import android.net.Uri;

import bolts.Task;
import chat.rocket.android.api.DDPClientWrapper;
import chat.rocket.android.api.FileUploadingHelper;
import chat.rocket.android.helper.LogcatIfError;
import chat.rocket.android.helper.OkHttpHelper;
import chat.rocket.android.log.RCLog;
import chat.rocket.android.model.SyncState;
import chat.rocket.android.model.internal.FileUploading;
import chat.rocket.android.realm_helper.RealmHelper;

import io.realm.Realm;
import io.realm.RealmResults;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSink;
import okio.Okio;
import okio.Source;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * execute file uploading and requesting sendMessage with attachment.
 */
public class FileUploadingToS3Observer extends AbstractModelObserver<FileUploading> {
  private FileUploadingHelper methodCall;

  public FileUploadingToS3Observer(Context context, String hostname,
                                   RealmHelper realmHelper, DDPClientWrapper ddpClient) {
    super(context, hostname, realmHelper, ddpClient);
    methodCall = new FileUploadingHelper(realmHelper, ddpClient);

    realmHelper.executeTransaction(realm -> {
      // resume pending operations.
      RealmResults<FileUploading> pendingUploadRequests = realm.where(FileUploading.class)
          .equalTo("syncstate", SyncState.SYNCING)
          .equalTo("storageType", FileUploading.STORAGE_TYPE_S3)
          .findAll();
      for (FileUploading req : pendingUploadRequests) {
        req.setSyncstate(SyncState.NOT_SYNCED);
      }

      // clean up records.
      realm.where(FileUploading.class)
          .beginGroup()
          .equalTo("syncstate", SyncState.SYNCED)
          .or()
          .equalTo("syncstate", SyncState.FAILED)
          .endGroup()
          .equalTo("storageType", FileUploading.STORAGE_TYPE_S3)
          .findAll().deleteAllFromRealm();
      return null;
    }).continueWith(new LogcatIfError());
  }

  @Override
  public RealmResults<FileUploading> queryItems(Realm realm) {
    return realm.where(FileUploading.class)
        .equalTo("syncstate", SyncState.NOT_SYNCED)
        .equalTo("storageType", FileUploading.STORAGE_TYPE_S3)
        .findAll();
  }

  @Override
  public void onUpdateResults(List<FileUploading> results) {
    if (results.isEmpty()) {
      return;
    }

    List<FileUploading> uploadingList = realmHelper.executeTransactionForReadResults(realm ->
        realm.where(FileUploading.class).equalTo("syncstate", SyncState.SYNCING).findAll());
    if (uploadingList.size() >= 3) {
      // do not upload more than 3 files simultaneously
      return;
    }

    FileUploading fileUploading = results.get(0);
    final String roomId = fileUploading.getRoomId();
    final String uplId = fileUploading.getUplId();
    final String filename = fileUploading.getFilename();
    final long filesize = fileUploading.getFilesize();
    final String mimeType = fileUploading.getMimeType();
    final Uri fileUri = Uri.parse(fileUploading.getUri());

    realmHelper.executeTransaction(realm ->
        realm.createOrUpdateObjectFromJson(FileUploading.class, new JSONObject()
            .put("uplId", uplId)
            .put("syncstate", SyncState.SYNCING)
        )
    ).onSuccessTask(_task -> methodCall.uploadRequest(filename, filesize, mimeType, roomId)
    ).onSuccessTask(task -> {
      final JSONObject info = task.getResult();
      final String uploadUrl = info.getString("upload");
      final String downloadUrl = info.getString("download");
      final JSONArray postDataList = info.getJSONArray("postData");

      MultipartBody.Builder bodyBuilder = new MultipartBody.Builder()
          .setType(MultipartBody.FORM);

      for (int i = 0; i < postDataList.length(); i++) {
        JSONObject postData = postDataList.getJSONObject(i);
        bodyBuilder.addFormDataPart(postData.getString("name"), postData.getString("value"));
      }

      bodyBuilder.addFormDataPart("file", filename,
          new RequestBody() {
            private long numBytes = 0;

            @Override
            public MediaType contentType() {
              return MediaType.parse(mimeType);
            }

            @Override
            public long contentLength() throws IOException {
              return filesize;
            }

            @Override
            public void writeTo(BufferedSink sink) throws IOException {
              InputStream inputStream = context.getContentResolver().openInputStream(fileUri);
              try (Source source = Okio.source(inputStream)) {
                long readBytes;
                while ((readBytes = source.read(sink.buffer(), 8192)) > 0) {
                  numBytes += readBytes;
                  realmHelper.executeTransaction(realm ->
                      realm.createOrUpdateObjectFromJson(FileUploading.class, new JSONObject()
                          .put("uplId", uplId)
                          .put("uploadedSize", numBytes)))
                      .continueWith(new LogcatIfError());
                }
              }
            }
          });

      Request request = new Request.Builder()
          .url(uploadUrl)
          .post(bodyBuilder.build())
          .build();

      Response response = OkHttpHelper.getClientForUploadFile().newCall(request).execute();
      if (response.isSuccessful()) {
        return Task.forResult(downloadUrl);
      } else {
        return Task.forError(new Exception(response.message()));
      }
    }).onSuccessTask(task -> {
      String downloadUrl = task.getResult();
      return methodCall.sendFileMessage(roomId, "s3", new JSONObject()
          .put("_id", Uri.parse(downloadUrl).getLastPathSegment())
          .put("type", mimeType)
          .put("size", filesize)
          .put("name", filename)
          .put("url", downloadUrl)
      );
    }).onSuccessTask(task -> realmHelper.executeTransaction(realm ->
        realm.createOrUpdateObjectFromJson(FileUploading.class, new JSONObject()
            .put("uplId", uplId)
            .put("syncstate", SyncState.SYNCED)
            .put("error", JSONObject.NULL)
        )
    )).continueWithTask(task -> {
      if (task.isFaulted()) {
        RCLog.w(task.getError());
        return realmHelper.executeTransaction(realm ->
            realm.createOrUpdateObjectFromJson(FileUploading.class, new JSONObject()
                .put("uplId", uplId)
                .put("syncstate", SyncState.FAILED)
                .put("error", task.getError().getMessage())
            ));
      } else {
        return Task.forResult(null);
      }
    });
  }
}
