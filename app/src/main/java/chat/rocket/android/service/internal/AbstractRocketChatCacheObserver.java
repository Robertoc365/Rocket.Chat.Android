package chat.rocket.android.service.internal;

import android.content.Context;
import android.content.SharedPreferences;
import chat.rocket.android.RocketChatCache;
import chat.rocket.android.helper.TextUtils;
import chat.rocket.android.model.ddp.RoomSubscription;
import chat.rocket.android.realm_helper.RealmHelper;
import chat.rocket.android.service.Registrable;

public abstract class AbstractRocketChatCacheObserver implements Registrable {
  private final Context context;
  private final RealmHelper realmHelper;
  private String roomId;

  protected AbstractRocketChatCacheObserver(Context context, RealmHelper realmHelper) {
    this.context = context;
    this.realmHelper = realmHelper;
  }

  private void updateRoomIdWith(SharedPreferences prefs) {
    String roomId = prefs.getString(RocketChatCache.KEY_SELECTED_ROOM_ID, null);
    if (!TextUtils.isEmpty(roomId)) {
      RoomSubscription room = realmHelper.executeTransactionForRead(realm ->
          realm.where(RoomSubscription.class).equalTo("rid", roomId).findFirst());
      if (room != null) {
        if (this.roomId == null || !this.roomId.equals(roomId)) {
          this.roomId = roomId;
          onRoomIdUpdated(roomId);
        }
        return;
      }
    }

    if (this.roomId != null) {
      this.roomId = null;
      onRoomIdUpdated(null);
    }
  }

  protected abstract void onRoomIdUpdated(String roomId);

  private SharedPreferences.OnSharedPreferenceChangeListener listener =
      (prefs, key) -> {
        if (RocketChatCache.KEY_SELECTED_ROOM_ID.equals(key)) {
          updateRoomIdWith(prefs);
        }
      };

  @Override public final void register() {
    SharedPreferences prefs = RocketChatCache.get(context);
    prefs.registerOnSharedPreferenceChangeListener(listener);
    updateRoomIdWith(prefs);
  }

  @Override public final void unregister() {
    RocketChatCache.get(context).unregisterOnSharedPreferenceChangeListener(listener);
  }
}
