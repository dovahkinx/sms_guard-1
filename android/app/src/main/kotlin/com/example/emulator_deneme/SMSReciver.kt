package com.dovahkin.sms_guard


import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.ContactsContract
import android.provider.Telephony
import android.telephony.SmsMessage
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.flutter.plugin.common.EventChannel
import org.tensorflow.lite.support.label.Category
import org.tensorflow.lite.task.core.BaseOptions
import org.tensorflow.lite.task.text.nlclassifier.BertNLClassifier
import java.util.concurrent.ConcurrentHashMap


class SMSReciver( private val eventSink: EventChannel.EventSink? ) : BroadcastReceiver() {
    companion object {
        const val NOTIFICATION_CHANNEL_ID = "sms_notification_channel"
        const val NOTIFICATION_CHANNEL_HIGH_ID = "sms_notification_high_channel" // Yüksek öncelikli kanal
        
        // Son 30 saniye içinde gösterilen bildirimleri izlemek için bir cache
        private val recentNotifications = ConcurrentHashMap<String, Long>()
        
        // Bildirimlerin tekrarlanmasını engellemek için kullanılan süre (milisaniye)
        private const val NOTIFICATION_DEBOUNCE_TIME = 5000L // 5 saniye
        
        // Bildirim gösterip göstermeyeceğimizi kontrol eden metot
        fun shouldShowNotification(uniqueKey: String): Boolean {
            val now = System.currentTimeMillis()
            val lastShown = recentNotifications[uniqueKey]
            
            // Daha önce bu bildirim gösterilmemiş veya gösterileli belirtilen süreden fazla zaman geçmiş
            if (lastShown == null || now - lastShown > NOTIFICATION_DEBOUNCE_TIME) {
                recentNotifications[uniqueKey] = now
                
                // Cache'i temizle (5 saniyeden eski bildirimleri sil)
                recentNotifications.entries.removeIf { now - it.value > NOTIFICATION_DEBOUNCE_TIME }
                
                return true
            }
            
            return false
        }
        
        // Bildirim sesi çalmak için MediaPlayer
        private var mediaPlayer: MediaPlayer? = null
        
        // Bildirim sesini manuel olarak çal
        fun playNotificationSound(context: Context) {
            try {
                // Önceki ses hala çalıyorsa durdur
                stopNotificationSound()
                
                // Varsayılan bildirim sesi URI'sini al
                val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                
                mediaPlayer = MediaPlayer.create(context.applicationContext, soundUri)
                mediaPlayer?.setAudioStreamType(AudioManager.STREAM_NOTIFICATION)
                mediaPlayer?.setOnCompletionListener { mp -> mp.release() }
                mediaPlayer?.start()
                
                // Titreşim ekle
                vibrate(context)
                
                println("Notification sound played successfully")
            } catch (e: Exception) {
                println("Error playing notification sound: ${e.message}")
                e.printStackTrace()
            }
        }
        
        // Bildirim sesini durdur
        fun stopNotificationSound() {
            try {
                mediaPlayer?.let {
                    if (it.isPlaying) {
                        it.stop()
                    }
                    it.release()
                }
                mediaPlayer = null
            } catch (e: Exception) {
                println("Error stopping notification sound: ${e.message}")
            }
        }
        
        // Titreşim fonksiyonu
        fun vibrate(context: Context) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                    val vibrator = vibratorManager.defaultVibrator
                    vibrator.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        vibrator.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
                    } else {
                        @Suppress("DEPRECATION")
                        vibrator.vibrate(500)
                    }
                }
            } catch (e: Exception) {
                println("Error during vibration: ${e.message}")
            }
        }
    }
    
    constructor() : this(null)

    override fun onReceive(context: Context, intent: Intent) {
        println("SMS received in SMSReceiver")

        // Sadece SMS_RECEIVED_ACTION eylemini işle
        if (intent.action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            intent?.extras?.let {
                try {
                    val pdus = it.get("pdus") as Array<*>
                    val msgs = arrayOfNulls<SmsMessage>(pdus.size)
                    val strBuilder = StringBuilder()
                    
                    for (i in msgs.indices) {
                        msgs[i] = SmsMessage.createFromPdu(pdus[i] as ByteArray)
                        strBuilder.append(msgs[i]?.messageBody)
                    }

                    val msgText = strBuilder.toString()
                    val msgFrom = msgs[0]?.originatingAddress

                    if (!msgFrom.isNullOrBlank() && !msgText.isNullOrBlank()) {
                        println("Processing SMS in SMSReceiver: from=$msgFrom, message=$msgText")
                        
                        // Bildirim sesini çal (bu satırı ekledik - daima çal)
                        playNotificationSound(context)
                        
                        // Flutter'a mesaj içeriğini gönder - sadece bir kez gönder
                        eventSink?.success(messageToMap(msgFrom, msgText))
                        
                        try {
                            // BERT sınıflandırıcı ile mesajı kontrol et
                            val result = bertClassifier(msgText, context)
                            
                            if (result != null) {
                                println("Classification result: $result")
                                
                                // Spam tespiti için skor eşiği (0.8)
                                if (result[0].score > result[1].score && result[0].score > 0.8) {
                                    // Spam mesaj olarak işaretle
                                    println("Spam message detected with score: ${result[0].score}")
                                    val db = DBHelper(context)
                                    db.insertData(msgText, msgFrom)
                                } else {
                                    // Normal mesaj - bildirim göster
                                    println("Normal message - showing notification")
                                    
                                    // Kişi adını bul
                                    val name = getcontactname(context, msgFrom)
                                    val displayName = if (name.isNotEmpty()) name else msgFrom
                                    
                                    // Bildirim göstermeden önce, bu SMS için son 5 saniye içinde bildirim gösterip göstermediğimizi kontrol et
                                    val notificationKey = "$msgFrom:$msgText".hashCode().toString()
                                    
                                    if (shouldShowNotification(notificationKey)) {
                                        // Bildirim kanalını oluştur (Android 8+ için)
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                            createNotificationChannel(context)
                                        }
                                        
                                        // Bildirimi göster
                                        showNotification(context, displayName, msgText)
                                    } else {
                                        println("Skipping duplicate notification for: $displayName - $msgText")
                                    }
                                    
                                    // SMS'i inbox'a kaydet
                                    saveinbox(context, msgFrom, msgText)
                                }
                            }
                        } catch (e: Exception) {
                            println("Error processing message: ${e.message}")
                            e.printStackTrace()
                            
                            // Hata olsa bile SMS'i kaydet
                            saveinbox(context, msgFrom, msgText)
                        }
                    }
                } catch (e: Exception) {
                    println("Error processing SMS: ${e.message}")
                    e.printStackTrace()
                }
            }
        }
    }
    
    // Flutter'a gönderilecek mesaj formatını hazırla
    private fun messageToMap(address: String, body: String): Map<String, Any?> {
        return mapOf(
            "body" to body,
            "address" to address,
            "date" to System.currentTimeMillis(),
            "thread_id" to "0",
            "read" to "0",
            "kind" to "inbox"
        )
    }
    
    @RequiresApi(Build.VERSION_CODES.O)
    private fun createNotificationChannel(context: Context) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        // Normal öncelikli kanal
        if (notificationManager.getNotificationChannel(NOTIFICATION_CHANNEL_ID) == null) {
            // Bildirim kanalı oluştur
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "SMS Bildirimleri",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "SMS mesaj bildirimleri"
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                enableVibration(true)
                enableLights(true)
                
                // Bildirim sesi ayarla (daha yüksek ses için alarm sesi kullanabilirsiniz)
                val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                val audioAttributes = AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .build()
                setSound(soundUri, audioAttributes)
            }
            
            notificationManager.createNotificationChannel(channel)
            println("Notification channel created: $NOTIFICATION_CHANNEL_ID")
        }
        
        // Yüksek öncelikli kanal (bu yeni eklenmiş bir kanal)
        if (notificationManager.getNotificationChannel(NOTIFICATION_CHANNEL_HIGH_ID) == null) {
            // Yüksek öncelikli bildirim kanalı oluştur
            val highChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_HIGH_ID,
                "Acil SMS Bildirimleri",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Önemli SMS mesaj bildirimleri"
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 200, 500) // Daha belirgin titreşim
                enableLights(true)
                
                // Alarm sesi kullan (daha yüksek sesli)
                val alarmSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) 
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                val audioAttributes = AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_ALARM) // Daha yüksek ses seviyesi için ALARM kullan
                    .build()
                setSound(alarmSoundUri, audioAttributes)
            }
            
            notificationManager.createNotificationChannel(highChannel)
            println("High priority notification channel created: $NOTIFICATION_CHANNEL_HIGH_ID")
        }
    }
    
    private fun showNotification(context: Context, title: String, message: String) {
        println("Preparing to show notification: $title - $message")
        
        // Uygulama açılması için intent
        val intent = context.packageManager.getLaunchIntentForPackage("com.dovahkin.sms_guard")
        
        val pendingIntentFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        
        val pendingIntent = PendingIntent.getActivity(context, 0, intent, pendingIntentFlag)
        
        // Bildirim ID'si oluştur (benzersiz olmalı)
        // Aynı konuşma için hep aynı ID kullanarak üzerine yazma yapacağız
        val notificationId = title.hashCode()
        
        // Hangi kanalı kullanacağımızı belirle
        val channelId = if (message.length < 30) {
            // Kısa mesajlar için normal kanal
            NOTIFICATION_CHANNEL_ID
        } else {
            // Uzun mesajlar için yüksek öncelikli kanal
            NOTIFICATION_CHANNEL_HIGH_ID
        }
        
        // Bildirim sesi URI'si
        val soundUri = if (channelId == NOTIFICATION_CHANNEL_HIGH_ID) {
            // Yüksek öncelikli bildirimler için alarm sesi
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) 
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        } else {
            // Normal bildirimler için bildirim sesi
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        }
        
        // Bildirim oluştur
        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_email) // Android'in yerleşik SMS ikonunu kullan
            .setContentTitle(title)
            .setContentText(message)
            .setContentIntent(pendingIntent)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setSound(soundUri)
            .setAutoCancel(true)
            .setDefaults(NotificationCompat.DEFAULT_ALL) // Tüm varsayılanları kullan (ses, titreşim, ışık)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(false) // Her zaman uyarı ver
        
        try {
            // Bildirimi göster
            val notificationManager = NotificationManagerCompat.from(context)
            
            // Android 13+ için bildirim izni kontrol et
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ActivityCompat.checkSelfPermission(
                        context, 
                        android.Manifest.permission.POST_NOTIFICATIONS
                    ) == PackageManager.PERMISSION_GRANTED) {
                    notificationManager.notify(notificationId, builder.build())
                    println("Notification posted with ID: $notificationId")
                } else {
                    println("Notification permission not granted")
                    // İzin yoksa eski yöntemle bildirim göstermeyi dene
                    val oldNotificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    oldNotificationManager.notify(notificationId, builder.build())
                }
            } else {
                // Android 13 öncesi için direkt göster
                notificationManager.notify(notificationId, builder.build())
                println("Notification posted with ID: $notificationId")
            }
            
            // Her durumda MediaPlayer ile sesi tekrar çalarak garanti edelim
            playNotificationSound(context)
            
        } catch (e: Exception) {
            println("Failed to show notification: ${e.message}")
            e.printStackTrace()
            
            // Bildirim gösterilememesi durumunda en azından ses çalalım
            playNotificationSound(context)
        }
    }
    
    fun saveinbox(context: Context, number: String, message: String): Uri? {
        println("Saving SMS to inbox: from=$number, message=$message")
        
        // Önce bu mesajın zaten var olup olmadığını kontrol et
        val cursor = context.contentResolver.query(
            Uri.parse("content://sms/inbox"),
            arrayOf(Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY),
            "${Telephony.Sms.ADDRESS} = ? AND ${Telephony.Sms.BODY} = ?",
            arrayOf(number, message),
            null
        )
        
        // Eğer bu aynı mesaj zaten varsa, yeni bir tane eklemeye gerek yok
        val alreadyExists = (cursor != null && cursor.count > 0)
        cursor?.close()
        
        if (alreadyExists) {
            println("SMS already exists in inbox, skipping insertion")
            return null
        }
        
        val smsValues = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, number)
            put(Telephony.Sms.BODY, message)
            put(Telephony.Sms.DATE, System.currentTimeMillis())
            // Mark as unread
            put(Telephony.Sms.READ, 0)
            // Mark as unseen
            put(Telephony.Sms.SEEN, 0)
            // Mark as inbox type - This is crucial!
            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
        }

        var uri: Uri? = null
        try {
            uri = context.contentResolver.insert(Uri.parse("content://sms/inbox"), smsValues)
            println("SMS saved successfully: $uri")
            return uri
        } catch (e: Exception) {
            println("Failed to save SMS: ${e.message}")
            e.printStackTrace()
            return null
        }
    }

    @SuppressLint("Range")
    fun getcontactname(context: Context, number: String): String {

        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(
                number
            )
        )

        val projection = arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME)

        var contactName = ""
        val cursor: Cursor? = context.contentResolver.query(uri, projection, null, null, null)

        if (cursor != null) {
            if (cursor.moveToFirst()) {
                contactName = cursor.getString(0)
            }
            cursor.close()
        }


        return contactName!!
    }

    fun SmsMessage.toMap(): HashMap<String, Any?> {
        val smsMap = HashMap<String, Any?>()
        this.apply {
            smsMap["message_body"] = messageBody
            smsMap["timestamp"] = timestampMillis.toString()
            smsMap["originating_address"] = originatingAddress
            smsMap["status"] = status.toString()
            smsMap["service_center"] = serviceCenterAddress
        }
        return smsMap
    }

    fun bertClassifier(message: String, context: Context): MutableList<Category>? {
        val options = BertNLClassifier.BertNLClassifierOptions
            .builder()
            .setBaseOptions(BaseOptions.builder().setNumThreads(4).build())
            .build()
        val bertClassifier = BertNLClassifier.createFromFileAndOptions(
            context,
            "mobilebert_son.tflite",
            options
        )
        val classifier = bertClassifier.classify(message)

        bertClassifier.close()
        println(classifier)
        return classifier
    }
}