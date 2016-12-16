package chat.rocket.android.service.observer;

import android.content.Context;
import chat.rocket.android.helper.CheckSum;
import chat.rocket.android.helper.LogcatIfError;
import chat.rocket.android.model.SyncState;
import chat.rocket.android.model.internal.MethodCall;
import chat.rocket.android.realm_helper.RealmHelper;
import chat.rocket.android.api.DDPClientWrapper;
import chat.rocket.android_ddp.DDPClientCallback;
import io.realm.Realm;
import io.realm.RealmResults;
import java.util.List;
import org.json.JSONObject;

/**
 * Observing MethodCall record, executing RPC if needed.
 */
public class MethodCallObserver extends AbstractModelObserver<MethodCall> {

  private String prevDigest;

  /**
   * constructor.
   */
  public MethodCallObserver(Context context, String hostname,
      RealmHelper realmHelper, DDPClientWrapper ddpClient) {
    super(context, hostname, realmHelper, ddpClient);
    realmHelper.executeTransaction(realm -> {
      // resume pending operations.
      RealmResults<MethodCall> pendingMethodCalls = realm.where(MethodCall.class)
          .equalTo("syncstate", SyncState.SYNCING)
          .findAll();
      for (MethodCall call : pendingMethodCalls) {
        call.setSyncState(SyncState.NOT_SYNCED);
      }

      // clean up records.
      realm.where(MethodCall.class)
          .beginGroup()
          .equalTo("syncstate", SyncState.SYNCED)
          .or()
          .equalTo("syncstate", SyncState.FAILED)
          .endGroup()
          .findAll().deleteAllFromRealm();
      return null;
    }).continueWith(new LogcatIfError());
  }

  @Override public RealmResults<MethodCall> queryItems(Realm realm) {
    return realm.where(MethodCall.class)
        .isNotNull("name")
        .equalTo("syncstate", SyncState.NOT_SYNCED)
        .findAll();
  }

  private String getDigestFor(List<MethodCall> results) {
    if (results == null) {
      return "-";
    }
    if (results.isEmpty()) {
      return "[]";
    }
    StringBuilder stringBuilder = new StringBuilder();
    for (MethodCall result : results) {
      stringBuilder.append(result.getMethodCallId());
    }
    return CheckSum.sha256(stringBuilder.toString());
  }

  @Override public void onUpdateResults(List<MethodCall> results) {
    String digest = getDigestFor(results);
    if (prevDigest == null) {
      if (digest == null) {
        return;
      }
    } else {
      if (prevDigest.equals(digest)) {
        return;
      }
    }
    prevDigest = digest;
    if (results == null || results.isEmpty()) {
      return;
    }

    MethodCall call = results.get(0);
    final String methodCallId = call.getMethodCallId();
    final String methodName = call.getName();
    final String params = call.getParamsJson();
    final long timeout = call.getTimeout();
    realmHelper.executeTransaction(realm ->
        realm.createOrUpdateObjectFromJson(MethodCall.class, new JSONObject()
            .put("methodCallId", methodCallId)
            .put("syncstate", SyncState.SYNCING))
    ).onSuccessTask(task ->
        ddpClient.rpc(methodCallId, methodName, params, timeout)
            .onSuccessTask(_task -> realmHelper.executeTransaction(realm -> {
              String json = _task.getResult().result;
              return realm.createOrUpdateObjectFromJson(MethodCall.class, new JSONObject()
                  .put("methodCallId", methodCallId)
                  .put("syncstate", SyncState.SYNCED)
                  .put("resultJson", json));
            }))
    ).continueWithTask(task -> {
      if (task.isFaulted()) {
        return realmHelper.executeTransaction(realm -> {
          Exception exception = task.getError();
          final String errMessage = (exception instanceof DDPClientCallback.RPC.Error)
              ? ((DDPClientCallback.RPC.Error) exception).error.toString()
              : exception.getMessage();
          realm.createOrUpdateObjectFromJson(MethodCall.class, new JSONObject()
              .put("methodCallId", methodCallId)
              .put("syncstate", SyncState.FAILED)
              .put("resultJson", errMessage));
          return null;
        });
      }
      return task;
    }).continueWith(new LogcatIfError());
  }
}
