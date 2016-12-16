package chat.rocket.android.model.ddp;

import chat.rocket.android.model.SyncState;

import io.realm.RealmObject;
import io.realm.annotations.PrimaryKey;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Message.
 */
@SuppressWarnings({"PMD.ShortClassName", "PMD.ShortVariable",
    "PMD.MethodNamingConventions", "PMD.VariableNamingConventions"})
public class Message extends RealmObject {
  //ref: Rocket.Chat:packages/rocketchat-lib/lib/MessageTypes.coffee

  @PrimaryKey private String _id;
  private String t; //type:
  private String rid; //roomId.
  private int syncstate;
  private long ts;
  private String msg;
  private User u;
  private boolean groupable;
  private String attachments; //JSONArray.
  private String urls; //JSONArray.

  public String getId() {
    return _id;
  }

  public void setId(String _id) {
    this._id = _id;
  }

  public String getType() {
    return t;
  }

  public void setType(String t) {
    this.t = t;
  }

  public String getRoomId() {
    return rid;
  }

  public void setRoomId(String rid) {
    this.rid = rid;
  }

  public int getSyncState() {
    return syncstate;
  }

  public void setSyncState(int syncstate) {
    this.syncstate = syncstate;
  }

  public long getTimeStamp() {
    return ts;
  }

  public void setTimeStamp(long ts) {
    this.ts = ts;
  }

  public String getMessage() {
    return msg;
  }

  public void setMessage(String msg) {
    this.msg = msg;
  }

  public User getUser() {
    return u;
  }

  public void setUser(User u) {
    this.u = u;
  }

  public boolean isGroupable() {
    return groupable;
  }

  public void setGroupable(boolean groupable) {
    this.groupable = groupable;
  }

  public String getAttachments() {
    return attachments;
  }

  public void setAttachments(String attachments) {
    this.attachments = attachments;
  }

  public String getUrls() {
    return urls;
  }

  public void setUrls(String urls) {
    this.urls = urls;
  }

  public static JSONObject customizeJson(JSONObject messageJson) throws JSONException {
    long ts = messageJson.getJSONObject("ts").getLong("$date");
    messageJson.remove("ts");
    messageJson.put("ts", ts).put("syncstate", SyncState.SYNCED);

    if (messageJson.isNull("groupable")) {
      messageJson.put("groupable", true);
    }

    return messageJson;
  }
}
