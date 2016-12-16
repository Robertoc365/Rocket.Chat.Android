package chat.rocket.android.fragment.chatroom.dialog;

import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v7.widget.GridLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.view.View;
import android.widget.TextView;
import chat.rocket.android.R;
import chat.rocket.android.helper.LogcatIfError;
import chat.rocket.android.layouthelper.chatroom.dialog.RoomUserAdapter;
import chat.rocket.android.log.RCLog;
import chat.rocket.android.model.SyncState;
import chat.rocket.android.model.internal.GetUsersOfRoomsProcedure;
import chat.rocket.android.realm_helper.RealmObjectObserver;
import chat.rocket.android.service.RocketChatService;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Dialog to show members in a room.
 */
public class UsersOfRoomDialogFragment extends AbstractChatroomDialogFragment {

  private String hostname;
  private RealmObjectObserver<GetUsersOfRoomsProcedure> procedureObserver;
  private int previousSyncState;

  public UsersOfRoomDialogFragment() {}

  /**
   * create UsersOfRoomDialogFragment with required parameters.
   */
  public static UsersOfRoomDialogFragment create(String serverConfigId,
      String roomId, String hostname) {
    Bundle args = new Bundle();
    args.putString("serverConfigId", serverConfigId);
    args.putString("roomId", roomId);
    args.putString("hostname", hostname);

    UsersOfRoomDialogFragment fragment = new UsersOfRoomDialogFragment();
    fragment.setArguments(args);

    return fragment;
  }

  @Override public void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    procedureObserver = realmHelper
        .createObjectObserver(realm ->
            realm.where(GetUsersOfRoomsProcedure.class).equalTo("roomId", roomId))
        .setOnUpdateListener(this::onUpdateGetUsersOfRoomProcedure);
    previousSyncState = SyncState.NOT_SYNCED;

    if (savedInstanceState == null) {
      requestGetUsersOfRoom();
    }
  }

  @Override protected void handleArgs(@NonNull Bundle args) {
    super.handleArgs(args);
    hostname = args.getString("hostname");
  }

  @Override protected int getLayout() {
    return R.layout.dialog_users_of_room;
  }

  @Override protected void onSetupDialog() {
    RecyclerView recyclerView = (RecyclerView) getDialog().findViewById(R.id.recyclerview);
    recyclerView.setLayoutManager(new GridLayoutManager(getContext(), 2));
    recyclerView.setAdapter(new RoomUserAdapter(getContext(), realmHelper, hostname));
  }

  private void requestGetUsersOfRoom() {
    realmHelper.executeTransaction(realm -> {
      realm.createOrUpdateObjectFromJson(GetUsersOfRoomsProcedure.class, new JSONObject()
          .put("roomId", roomId)
          .put("syncstate", SyncState.NOT_SYNCED)
          .put("showAll", true));
      return null;
    }).onSuccessTask(task -> {
      RocketChatService.keepalive(getContext());
      return task;
    }).continueWith(new LogcatIfError());
  }

  @Override public void onResume() {
    super.onResume();
    procedureObserver.sub();
  }

  @Override public void onPause() {
    procedureObserver.unsub();
    super.onPause();
  }

  private void onUpdateGetUsersOfRoomProcedure(GetUsersOfRoomsProcedure procedure) {
    if (procedure == null) {
      return;
    }

    int syncstate = procedure.getSyncState();
    if (previousSyncState != syncstate) {
      onSyncStateUpdated(syncstate);
      previousSyncState = syncstate;
    }

    if (syncstate == SyncState.SYNCED) {
      onRenderTotalCount(procedure.getTotal());

      try {
        JSONArray array = new JSONArray(procedure.getRecords());
        ArrayList<String> users = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
          users.add(array.getString(i));
        }
        onRenderUsers(users);
      } catch (JSONException exception) {
        RCLog.e(exception);
      }
    }
  }

  /**
   * called only if prevSyncstate != newSyncstate.
   */
  private void onSyncStateUpdated(int newSyncstate) {
    boolean show = newSyncstate == SyncState.NOT_SYNCED || newSyncstate == SyncState.SYNCING;
    getDialog().findViewById(R.id.waiting).setVisibility(show ? View.VISIBLE : View.GONE);
  }

  /**
   * called only if syncstate = SYNCED.
   */
  private void onRenderTotalCount(long total) {
    TextView userCount = (TextView) getDialog().findViewById(R.id.room_user_count);
    userCount.setText(getString(R.string.fmt_room_user_count, total));
  }

  /**
   * called only if syncstate = SYNCED.
   */
  private void onRenderUsers(List<String> usernames) {
    RecyclerView recyclerView = (RecyclerView) getDialog().findViewById(R.id.recyclerview);
    if (recyclerView != null && recyclerView.getAdapter() instanceof RoomUserAdapter) {
      ((RoomUserAdapter) recyclerView.getAdapter()).setUsernames(usernames);
    }
  }
}
