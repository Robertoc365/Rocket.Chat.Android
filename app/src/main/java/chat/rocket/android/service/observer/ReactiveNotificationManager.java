package chat.rocket.android.service.observer;

import android.content.Context;
import chat.rocket.android.api.DDPClientWrapper;
import chat.rocket.android.helper.LogcatIfError;
import chat.rocket.android.log.RCLog;
import chat.rocket.android.model.ddp.RoomSubscription;
import chat.rocket.android.model.internal.NotificationItem;
import chat.rocket.android.realm_helper.RealmHelper;
import io.realm.Realm;
import io.realm.RealmResults;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * observing room subscriptions with unread>0.
 */
public class ReactiveNotificationManager extends AbstractModelObserver<RoomSubscription> {
  public ReactiveNotificationManager(Context context, String hostname,
      RealmHelper realmHelper, DDPClientWrapper ddpClient) {
    super(context, hostname, realmHelper, ddpClient);
  }

  @Override public RealmResults<RoomSubscription> queryItems(Realm realm) {
    return realm.where(RoomSubscription.class)
        .equalTo("open", true)
        .findAll();
  }

  @Override public void onUpdateResults(List<RoomSubscription> roomSubscriptions) {
    JSONArray notifications = new JSONArray();
    for (RoomSubscription roomSubscription : roomSubscriptions) {
      final String roomId = roomSubscription.getRoomId();
      NotificationItem item = realmHelper.executeTransactionForRead(realm ->
          realm.where(NotificationItem.class).equalTo("roomId", roomId).findFirst());

      long lastSeenAt = Math.max(item != null ? item.getLastSeenAt() : 0, roomSubscription.getLastSeen());
      try {
        JSONObject notification = new JSONObject()
            .put("roomId", roomSubscription.getRoomId())
            .put("title", roomSubscription.getName())
            .put("description", "new message")
            .put("unreadCount", roomSubscription.getUnread())
            .put("contentUpdatedAt", roomSubscription.getUpdatedAt())
            .put("lastSeenAt", lastSeenAt);

        if (RoomSubscription.TYPE_DIRECT_MESSAGE.equals(roomSubscription.getType())) {
          notification.put("senderName", roomSubscription.getName());
        } else {
          notification.put("senderName", JSONObject.NULL);
        }

        notifications.put(notification);
      } catch (JSONException exception) {
        RCLog.w(exception);
      }
    }

    realmHelper.executeTransaction(realm -> {
      realm.createOrUpdateAllFromJson(NotificationItem.class, notifications);
      return null;
    }).continueWith(new LogcatIfError());
  }
}
