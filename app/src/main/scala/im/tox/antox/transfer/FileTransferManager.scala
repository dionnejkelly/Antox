package im.tox.antox.transfer

import java.io.File

import android.content.Context
import android.os.Environment
import android.preference.PreferenceManager
import android.util.Log
import im.tox.antox.data.{AntoxDB, State}
import im.tox.antox.tox.{IntervalLevels, Intervals, Reactive, ToxSingleton}
import im.tox.antox.utils.BitmapManager
import im.tox.antox.wrapper.{ToxKey, FileKind}
import im.tox.antox.wrapper.FileKind.AVATAR
import im.tox.tox4j.core.enums.ToxFileControl

class FileTransferManager extends Intervals {
  private val TAG = "FileTransferManager"

  private var _transfers: Map[Long, FileTransfer] = Map[Long, FileTransfer]()
  private var _keyAndFileNumberToId: Map[(ToxKey, Integer), Long] = Map[(ToxKey, Integer), Long]()

  def isTransferring: Boolean = _transfers.exists(_._2.status == FileStatus.INPROGRESS)

  override def interval: Int = {
    if (isTransferring)
      IntervalLevels.WORKING.id
    else
      IntervalLevels.AWAKE.id
  }

  def add(t: FileTransfer) = {
    _transfers = _transfers + (t.id -> t)
    _keyAndFileNumberToId = _keyAndFileNumberToId + ((t.key, t.fileNumber) -> t.id)
  }

  def remove(id: Long): Unit = {
    val mTransfer = this.get(id)
    mTransfer match {
      case Some(t) => 
    _transfers = _transfers - id
    _keyAndFileNumberToId = _keyAndFileNumberToId - ((t.key, t.fileNumber))
      case None =>
    }
  }

  def remove(key: ToxKey, fileNumber: Integer): Unit = {
    val mId = _keyAndFileNumberToId.get(key, fileNumber)
    mId match {
      case Some(id) => this.remove(id)
      case None => 
    }
  }

  def get(id: Long): Option[FileTransfer] = {
    _transfers.get(id)
  }

  def get(toxKey: ToxKey, fileNumber: Integer): Option[FileTransfer] = {
    val mId = _keyAndFileNumberToId.get(toxKey, fileNumber)
    mId match {
      case Some(key) => this.get(key)
      case None => None
    }
  }


  def sendFileSendRequest(path: String, key: ToxKey, fileKind: FileKind, fileId: String = null, context: Context) {
    val file = new File(path)
    val splitPath = path.split("/")
    val fileName = splitPath(splitPath.length - 1)
    val splitFileName = fileName.span(_ != '.')
    val extension = splitFileName._2
    val name = splitFileName._1
    val nameTruncated = name.slice(0, 64 - 1 - extension.length)
    Log.d(TAG, "sendFileSendRequest")
    if (fileName != null) {
      require(key != null)
      ToxSingleton.getAntoxFriend(key)
        .map(_.getFriendNumber)
        .flatMap(friendNumber => {
        try {
          Log.d(TAG, "Creating tox file sender")
          val fileNumber = ToxSingleton.tox.fileSend(friendNumber, fileKind.kindId, file.length(), fileId, fileName)
          fileNumber match {
            case -1 => None
            case x => Some(x)
          }
        } catch {
          case e: Exception => {
            e.printStackTrace()
            None
          }
        }
      }).foreach(fileNumber => {
        val antoxDB = new AntoxDB(context)
        Log.d(TAG, "adding File Transfer")
        val id = antoxDB.addFileTransfer(key, path, fileNumber, fileKind.kindId, file.length.toInt, sending = true)
        State.transfers.add(new FileTransfer(key, file, fileNumber, file.length, 0, true, FileStatus.REQUESTSENT, id, fileKind))
        antoxDB.close()
      })
    }

    ToxSingleton.updateContactsList(context)
    ToxSingleton.updateMessages(context)
  }

  def sendFileDeleteRequest(key: ToxKey, fileKind: FileKind, context: Context): Unit = {
    ToxSingleton.getAntoxFriend(key).foreach(f => {
      ToxSingleton.tox.fileSend(f.getFriendNumber, AVATAR.kindId, 0, null, "")
      if (fileKind == FileKind.AVATAR) {
        onSelfAvatarSendFinished(key, context)
      }
    })
  }

  def fileSendRequest(key: ToxKey,
                      fileNumber: Int,
                      fileName: String,
                      fileKind: FileKind,
                      fileSize: Long,
                      replaceExisting: Boolean,
                      context: Context) {
    Log.d(TAG, "fileSendRequest")
    var fileN = fileName
    val fileSplit = fileName.split("\\.")
    var filePre = ""
    val fileExt = fileSplit(fileSplit.length - 1)
    for (j <- 0 until fileSplit.length - 1) {
      filePre = filePre.concat(fileSplit(j))
      if (j < fileSplit.length - 2) {
        filePre = filePre.concat(".")
      }
    }

    var file = new File(fileKind.getStorageDir(context).getPath, fileN)
    if (replaceExisting) file.delete()

    if (file.exists()) {
      var i = 1
      do {
        fileN = filePre + "(" + java.lang.Integer.toString(i) + ")" +
          "." +
          fileExt
        file = new File(fileKind.getStorageDir(context).getPath, fileN)
        i += 1
      } while (file.exists())
    }

    val antoxDB = new AntoxDB(context)
    val id = antoxDB.addFileTransfer(key, fileN, fileNumber, fileKind.kindId, fileSize.toInt, sending = false)
    State.transfers.add(new FileTransfer(key, file, fileNumber, fileSize, 0, false, FileStatus.REQUESTSENT, id, fileKind))
    antoxDB.close()
    ToxSingleton.updateMessages(context)
  }

  private def fileAcceptOrReject(key: ToxKey, fileNumber: Integer, context: Context, accept: Boolean) {
    Log.d(TAG, "fileAcceptReject, accepting: " + accept)
    val id = State.db.getFileId(key, fileNumber)
    if (id != -1) {
      val mFriend = ToxSingleton.getAntoxFriend(key)
      mFriend.foreach(friend => {
        try {
          ToxSingleton.tox.fileControl(friend.getFriendNumber, fileNumber,
            if (accept) ToxFileControl.RESUME else ToxFileControl.CANCEL)

          if (accept) {
            State.db.fileTransferStarted(key, fileNumber)
          } else {
            State.db.clearFileNumber(key, fileNumber)
          }

          val transfer = State.transfers.get(id)
          transfer match {
            case Some(t) =>
              if (accept) t.status = FileStatus.INPROGRESS else t.status = FileStatus.CANCELLED
            case None =>
          }
        } catch {
          case e: Exception => e.printStackTrace()
        }
      })
      Reactive.updatedMessages.onNext(true)
    }
  }

  def acceptFile(key: ToxKey, fileNumber: Int, context: Context) = fileAcceptOrReject(key, fileNumber, context, accept = true)

  def rejectFile(key: ToxKey, fileNumber: Int, context: Context) = fileAcceptOrReject(key, fileNumber, context, accept = false)

  def receiveFileData(key: ToxKey,
                      fileNumber: Int,
                      data: Array[Byte],
                      context: Context) {
    val mTransfer = State.transfers.get(key, fileNumber)
    val state = Environment.getExternalStorageState
    if (Environment.MEDIA_MOUNTED == state) {
      mTransfer match {
        case Some(t) =>
          t.writeData(data)
        case None =>
      }
    }
  }

  def getProgressSinceXAgo(id: Long, ms: Long): Option[(Long, Long)] = {
    val mTransfer = State.transfers.get(id)
    mTransfer match {
      case Some(t) => t.getProgressSinceXAgo(ms)
      case None => None
    }
  }

  def fileFinished(key: ToxKey, fileNumber: Integer, context: Context) {
    Log.d(TAG, "fileFinished")
    val transfer = State.transfers.get(key, fileNumber)
    transfer match {
      case Some(t) =>
        t.status = FileStatus.FINISHED
        val mFriend = ToxSingleton.getAntoxFriend(t.key)
        State.db.fileFinished(key, fileNumber)
        if (t.fileKind == FileKind.AVATAR) {
          if (t.sending) {
            onSelfAvatarSendFinished(key, context)
          } else {
            // Set current avatar as invalid in avatar cache in order to get it updated to new avatar
            BitmapManager.setAvatarInvalid(t.file)

            mFriend.get.setAvatar(Some(t.file))
            val db = new AntoxDB(context)
            db.updateFriendAvatar(key, t.file.getName)
            db.close()
          }
        }
        Reactive.updatedMessages.onNext(true)
        ToxSingleton.updateFriendsList(context)
        ToxSingleton.updateGroupList(context)

      case None => Log.d(TAG, "fileFinished: No transfer found")
    }
  }

  def cancelFile(key: ToxKey, fileNumber: Int, context: Context) {
    Log.d(TAG, "cancelFile")
    val db = new AntoxDB(context)
    State.transfers.remove(key, fileNumber)
    db.clearFileNumber(key, fileNumber)
    db.close()
    Reactive.updatedMessages.onNext(true)
  }

  def getProgress(id: Long): Long = {
    val mTransfer = State.transfers.get(id)
    mTransfer match {
      case Some(t) => t.progress
      case None => 0
    }
  }

  def fileTransferStarted(key: ToxKey, fileNumber: Integer, ctx: Context) {
    Log.d(TAG, "fileTransferStarted")
    State.db.fileTransferStarted(key, fileNumber)
  }

  def pauseFile(id: Long, ctx: Context) {
    Log.d(TAG, "pauseFile")
    val mTransfer = State.transfers.get(id)
    mTransfer match {
      case Some(t) => t.status = FileStatus.PAUSED
      case None =>
    }
  }

  def onSelfAvatarSendFinished(sentTo: ToxKey, context: Context): Unit = {
    val db = new AntoxDB(context)
    db.updateContactReceivedAvatar(sentTo, receivedAvatar = true)
    updateSelfAvatar(context)
    db.close()
  }

  def updateSelfAvatar(context: Context): Unit = {
    val db = new AntoxDB(context)
    db.getFriendList.filter(_.online).find(!_.receivedAvatar) match {
      case Some(friend) =>
        AVATAR.getAvatarFile(PreferenceManager.getDefaultSharedPreferences(context).getString("avatar", ""), context) match {
          case Some(file) =>
            println(file.length())
            sendFileSendRequest(file.getPath, friend.key, AVATAR, fileId = ToxSingleton.tox.hash(file).orNull, context = context)
          case None =>
            sendFileDeleteRequest(friend.key, AVATAR, context)
        }

      case None =>
        //avatar has been sent to all friends, do nothing
    }
  }
}
