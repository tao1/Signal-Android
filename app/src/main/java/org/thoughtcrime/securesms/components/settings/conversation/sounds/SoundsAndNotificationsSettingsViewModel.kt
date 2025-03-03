package org.thoughtcrime.securesms.components.settings.conversation.sounds

import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import org.thoughtcrime.securesms.database.RecipientDatabase
import org.thoughtcrime.securesms.notifications.NotificationChannels
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientId
import org.thoughtcrime.securesms.util.livedata.Store

class SoundsAndNotificationsSettingsViewModel(
  private val recipientId: RecipientId,
  private val repository: SoundsAndNotificationsSettingsRepository
) : ViewModel() {

  private val store = Store(SoundsAndNotificationsSettingsState())

  val state: LiveData<SoundsAndNotificationsSettingsState> = store.stateLiveData

  init {
    store.update(Recipient.live(recipientId).liveData) { recipient, state ->
      state.copy(
        recipientId = recipientId,
        muteUntil = if (recipient.isMuted) recipient.muteUntil else 0L,
        mentionSetting = recipient.mentionSetting,
        hasMentionsSupport = recipient.isPushV2Group,
        hasCustomNotificationSettings = recipient.notificationChannel != null || !NotificationChannels.supported()
      )
    }
  }

  fun setMuteUntil(muteUntil: Long) {
    repository.setMuteUntil(recipientId, muteUntil)
  }

  fun unmute() {
    repository.setMuteUntil(recipientId, 0L)
  }

  fun setMentionSetting(mentionSetting: RecipientDatabase.MentionSetting) {
    repository.setMentionSetting(recipientId, mentionSetting)
  }

  class Factory(
    private val recipientId: RecipientId,
    private val repository: SoundsAndNotificationsSettingsRepository
  ) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
      return requireNotNull(modelClass.cast(SoundsAndNotificationsSettingsViewModel(recipientId, repository)))
    }
  }
}
