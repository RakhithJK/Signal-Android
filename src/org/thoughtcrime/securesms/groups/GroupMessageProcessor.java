package org.thoughtcrime.securesms.groups;


import android.content.Context;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;

import com.google.protobuf.ByteString;

import org.thoughtcrime.securesms.ApplicationContext;
import org.thoughtcrime.securesms.database.Address;
import org.thoughtcrime.securesms.database.DatabaseFactory;
import org.thoughtcrime.securesms.database.GroupDatabase;
import org.thoughtcrime.securesms.database.MessagingDatabase.InsertResult;
import org.thoughtcrime.securesms.database.MmsDatabase;
import org.thoughtcrime.securesms.database.SmsDatabase;
import org.thoughtcrime.securesms.jobs.AvatarDownloadJob;
import org.thoughtcrime.securesms.jobs.PushGroupUpdateJob;
import org.thoughtcrime.securesms.mms.MmsException;
import org.thoughtcrime.securesms.mms.OutgoingGroupMediaMessage;
import org.thoughtcrime.securesms.notifications.MessageNotifier;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.sms.IncomingGroupMessage;
import org.thoughtcrime.securesms.sms.IncomingTextMessage;
import org.thoughtcrime.securesms.util.Base64;
import org.thoughtcrime.securesms.util.GroupUtil;
import org.whispersystems.libsignal.util.guava.Optional;
import org.whispersystems.signalservice.api.messages.SignalServiceAttachment;
import org.whispersystems.signalservice.api.messages.SignalServiceDataMessage;
import org.whispersystems.signalservice.api.messages.SignalServiceEnvelope;
import org.whispersystems.signalservice.api.messages.SignalServiceGroup;
import org.whispersystems.signalservice.api.messages.SignalServiceGroup.Type;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.thoughtcrime.securesms.database.GroupDatabase.GroupRecord;
import static org.whispersystems.signalservice.internal.push.SignalServiceProtos.AttachmentPointer;
import static org.whispersystems.signalservice.internal.push.SignalServiceProtos.GroupContext;

public class GroupMessageProcessor {

  private static final String TAG = GroupMessageProcessor.class.getSimpleName();

  public static @Nullable Long process(@NonNull Context context,
                                       @NonNull SignalServiceEnvelope envelope,
                                       @NonNull SignalServiceDataMessage message,
                                       SignalART art,
                                       boolean outgoing)
  {//Wichtig: Nur Nachrichten mit GroupID oder nicht ex. groupInfo akzeptiert
    if (!message.getGroupInfo().isPresent() || message.getGroupInfo().get().getGroupId() == null) {
      Log.w(TAG, "Received group message with no id! Ignoring...");
      return null;
    }
 //Wichtig: Rufe Gruppendatenbank auf, hole groupInfo, GroupID, (GroupRecord)
    GroupDatabase         database = DatabaseFactory.getGroupDatabase(context);
    SignalServiceGroup    group    = message.getGroupInfo().get();
    String                id       = GroupUtil.getEncodedId(group.getGroupId(), false);
    Optional<GroupRecord> record   = database.getGroup(id);

    if (record.isPresent() && group.getType() == Type.UPDATE) { //Wichtig: GroupREcord existiert & Typ=Update
      return handleGroupUpdate(context, envelope, group, record.get(), art, outgoing); //GroupUpdate mit hole Record
    } else if (!record.isPresent() && group.getType() == Type.UPDATE) {// GroupRecord existiert nicht und Typ=Update
      return handleGroupCreate(context, envelope, group, art, outgoing); //GroupUpdate ohne hole Record
    } else if (record.isPresent() && group.getType() == Type.QUIT) { //Record existiert und Typ=Quit
      return handleGroupLeave(context, envelope, group, record.get(), art, outgoing); //Behandle Gruppe verlassen
    } else if (record.isPresent() && group.getType() == Type.REQUEST_INFO) { //Record existiert und Typ=Info
      return handleGroupInfoRequest(context, envelope, group, record.get(), art); //Behandle Gruppenauskunft
    } else {
      Log.w(TAG, "Received unknown type, ignoring..."); //sonst ignoriere
      return null;
    }
  }

  private static @Nullable Long handleGroupCreate(@NonNull Context context,
                                                  @NonNull SignalServiceEnvelope envelope,
                                                  @NonNull SignalServiceGroup group,
                                                  SignalART signalART,
                                                  boolean outgoing)
  {
    GroupDatabase        database = DatabaseFactory.getGroupDatabase(context);
    String               id       = GroupUtil.getEncodedId(group.getGroupId(), false);
    GroupContext.Builder builder  = createGroupContext(group);
    builder.setType(GroupContext.Type.UPDATE); //Wichtig: Setze Typ auf Update

    BuildART buildART = new BuildART();
    Map<Integer, byte[]> setupART = buildART.setupART(context, group, database.getGroup(id));

    SignalServiceAttachment avatar  = group.getAvatar().orNull();
    List<Address>           members = group.getMembers().isPresent() ? new LinkedList<Address>() : null;

    int memberCount = 0;
    if (group.getMembers().isPresent()) { //Wichtig: Erzeuge Liste der Mitglieder
      for (String member : group.getMembers().get()) {
        members.add(Address.fromExternal(context, member));
        memberCount +=1;
        setupART.get(memberCount);
      }
    }
//erzeuge Datenbankeintrag
    database.create(id, group.getName().orNull(), members,
                    avatar != null && avatar.isPointer() ? avatar.asPointer() : null,
                    envelope.getRelay());

    return storeMessage(context, envelope, group, builder.build(), outgoing); 
  }

  private static @Nullable Long handleGroupUpdate(@NonNull Context context,
                                                  @NonNull SignalServiceEnvelope envelope,
                                                  @NonNull SignalServiceGroup group,
                                                  @NonNull GroupRecord groupRecord,
                                                  SignalART art, boolean outgoing)
  {

    GroupDatabase database = DatabaseFactory.getGroupDatabase(context);
    String        id       = GroupUtil.getEncodedId(group.getGroupId(), false);

    Set<Address> recordMembers = new HashSet<>(groupRecord.getMembers());
    Set<Address> messageMembers = new HashSet<>();

    for (String messageMember : group.getMembers().get()) {
      messageMembers.add(Address.fromExternal(context, messageMember));
    }

    Set<Address> addedMembers = new HashSet<>(messageMembers);
    addedMembers.removeAll(recordMembers);

    Set<Address> missingMembers = new HashSet<>(recordMembers);
    missingMembers.removeAll(messageMembers);

    GroupContext.Builder builder = createGroupContext(group);
    builder.setType(GroupContext.Type.UPDATE);

    if (addedMembers.size() > 0) {//Wichtig: Fuege neue Mitgleider hinzu
      Set<Address> unionMembers = new HashSet<>(recordMembers);
      unionMembers.addAll(messageMembers);
      database.updateMembers(id, new LinkedList<>(unionMembers));

      builder.clearMembers();

      for (Address addedMember : addedMembers) {
        builder.addMembers(addedMember.serialize());
      }
    } else {
      builder.clearMembers();
    }

    if (missingMembers.size() > 0) {
      // TODO We should tell added and missing about each-other.
    }

    if (group.getName().isPresent() || group.getAvatar().isPresent()) {
      SignalServiceAttachment avatar = group.getAvatar().orNull();
      database.update(id, group.getName().orNull(), avatar != null ? avatar.asPointer() : null);
    }

    if (group.getName().isPresent() && group.getName().get().equals(groupRecord.getTitle())) {
      builder.clearName();
    }

    if (!groupRecord.isActive()) database.setActive(id, true);

    return storeMessage(context, envelope, group, builder.build(), outgoing);
  }

  private static Long handleGroupInfoRequest(@NonNull Context context,
                                             @NonNull SignalServiceEnvelope envelope,
                                             @NonNull SignalServiceGroup group,
                                             @NonNull GroupRecord record, SignalART art)
  {
    if (record.getMembers().contains(Address.fromExternal(context, envelope.getSource()))) {
      ApplicationContext.getInstance(context)
                        .getJobManager()
                        .add(new PushGroupUpdateJob(context, envelope.getSource(), group.getGroupId()));
    }

    return null;
  }

  private static Long handleGroupLeave(@NonNull Context context,
                                       @NonNull SignalServiceEnvelope envelope,
                                       @NonNull SignalServiceGroup group,
                                       @NonNull GroupRecord record,
                                       SignalART art, boolean outgoing)
  {
    GroupDatabase database = DatabaseFactory.getGroupDatabase(context);
    String        id       = GroupUtil.getEncodedId(group.getGroupId(), false);
    List<Address> members  = record.getMembers();

    GroupContext.Builder builder = createGroupContext(group);
    builder.setType(GroupContext.Type.QUIT);

    if (members.contains(Address.fromExternal(context, envelope.getSource()))) {
      database.remove(id, Address.fromExternal(context, envelope.getSource()));
      if (outgoing) database.setActive(id, false);

      return storeMessage(context, envelope, group, builder.build(), outgoing);
    }

    return null;
  }


  private static @Nullable Long storeMessage(@NonNull Context context,
                                             @NonNull SignalServiceEnvelope envelope,
                                             @NonNull SignalServiceGroup group,
                                             @NonNull GroupContext storage,
                                             boolean  outgoing)
  {
    if (group.getAvatar().isPresent()) { //Wichtig: Falls Avatar existiert downloaden
      ApplicationContext.getInstance(context).getJobManager()
                        .add(new AvatarDownloadJob(context, group.getGroupId()));
    }

    try { //Wichtig: Falls Outgoing==true setze: mmsDatabase und Recipient aus Context, Adresse aus Context und encoded GroupID
      if (outgoing) { //erzeuge neue ausgehende Gruppennachricht, setze ThreadID und MessageID
        MmsDatabase               mmsDatabase     = DatabaseFactory.getMmsDatabase(context);
        Address                   addres          = Address.fromExternal(context, GroupUtil.getEncodedId(group.getGroupId(), false));
        Recipient                 recipient       = Recipient.from(context, addres, false);
        OutgoingGroupMediaMessage outgoingMessage = new OutgoingGroupMediaMessage(recipient, storage, null, envelope.getTimestamp(), 0, null, Collections.emptyList());
        long                      threadId        = DatabaseFactory.getThreadDatabase(context).getThreadIdFor(recipient);
        long                      messageId       = mmsDatabase.insertMessageOutbox(outgoingMessage, threadId, false, null);

        mmsDatabase.markAsSent(messageId, true); //markiere Nachricht als gesendet

        return threadId;
      } else { //Wichtig: falls Outgoing==false: hole smsDatabase, encodiere body, erzeuge incomingTextMessage und incommingGroupMessage
        SmsDatabase          smsDatabase  = DatabaseFactory.getSmsDatabase(context);
        String               body         = Base64.encodeBytes(storage.toByteArray());
        IncomingTextMessage  incoming     = new IncomingTextMessage(Address.fromExternal(context, envelope.getSource()), envelope.getSourceDevice(), envelope.getTimestamp(), body, Optional.of(group), 0);
        IncomingGroupMessage groupMessage = new IncomingGroupMessage(incoming, storage, body);

        Optional<InsertResult> insertResult = smsDatabase.insertMessageInbox(groupMessage);

        if (insertResult.isPresent()) {
          MessageNotifier.updateNotification(context, insertResult.get().getThreadId());
          return insertResult.get().getThreadId();
        } else {
          return null;
        }
      }
    } catch (MmsException e) {
      Log.w(TAG, e);
    }

    return null;
  }

  private static GroupContext.Builder createGroupContext(SignalServiceGroup group) {
    GroupContext.Builder builder = GroupContext.newBuilder();
    builder.setId(ByteString.copyFrom(group.getGroupId()));

    if (group.getAvatar().isPresent() && group.getAvatar().get().isPointer()) {
      builder.setAvatar(AttachmentPointer.newBuilder()
                                         .setId(group.getAvatar().get().asPointer().getId())
                                         .setKey(ByteString.copyFrom(group.getAvatar().get().asPointer().getKey()))
                                         .setContentType(group.getAvatar().get().getContentType()));
    }

    if (group.getName().isPresent()) {
      builder.setName(group.getName().get());
    }

    if (group.getMembers().isPresent()) {
      builder.addAllMembers(group.getMembers().get());
    }

    return builder;
  }

}
