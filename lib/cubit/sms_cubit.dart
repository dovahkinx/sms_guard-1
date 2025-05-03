import 'dart:async';

import 'package:another_telephony/telephony.dart' show Telephony;
import 'package:equatable/equatable.dart';
import 'package:fast_contacts/fast_contacts.dart';
import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart' show Cubit;
import 'package:flutter_sms_inbox/flutter_sms_inbox.dart' show SmsMessage, SmsQuery, SmsQueryKind;

import 'package:sms_guard/model/my_message_model.dart';

import 'package:sqflite/sqflite.dart';


import '../../model/search_sms_model.dart';
import '../../model/spam_model.dart';

part 'sms_state.dart';

class SmsCubit extends Cubit<SmsState> {
  SmsCubit() : super(SmsState()) {
    Telephony.instance.listenIncomingSms(onNewMessage: onNewMessage, listenInBackground: false);

    onInit();
  }

  // Flags to prevent concurrent operations
  bool _gettingMessages = false;
  bool _gettingContacts = false;
  
  void onInit() async {
    emit(state.copyWith(isLoading: true));
    SmsQuery query = SmsQuery();
    var messages = await query.getAllSms;
    
    // Get contacts once at startup
    await _getContactsIfNeeded();
    
    emit(state.copyWith(messages: messages));
    await getSpam();
    await getMessages();

    emit(state.copyWith(isLoading: false));
  }

  // Helper to avoid concurrent contact fetching
  Future<List<Contact>> _getContactsIfNeeded() async {
    if (_gettingContacts) {
      // Return current contacts if a fetch is already in progress
      return state.contactList;
    }
    
    if (state.contactList.isNotEmpty) {
      return state.contactList;
    }
    
    _gettingContacts = true;
    try {
      final List<Contact> contacts = await FastContacts.getAllContacts();
      emit(state.copyWith(contactList: contacts));
      return contacts;
    } catch (e) {
      print("Error getting contacts: $e");
      return [];
    } finally {
      _gettingContacts = false;
    }
  }

  void prinnt(text) {
    emit(state.copyWith(text: text));
  }

  void resultContactWithTextEditingController(text) async {
    List<Contact> result = [];
    if (text == "") {
      emit(state.copyWith(sendResult: []));
    } else {
      for (var item in state.contactList) {
        if (item.displayName.toLowerCase().contains(text.toLowerCase())) {
          if (item.phones.isNotEmpty) {
            result.add(item);
          } else {
            print("telefon yok");
          }
        }
      }
    }

    emit(state.copyWith(sendResult: result));
  }

  void onNewMessage(dynamic message) async {
    print("Yeni mesaj alındı: $message");
    
    try {
      // UI'ı yükleme durumuna getir
      emit(state.copyWith(isLoading: true));
      
      if (message is String) {
        print("String mesaj alındı: $message");
        
        // Allow system a moment to process the message
        await Future.delayed(Duration(milliseconds: 300));
        
        // Trigger a full refresh to get the new message
        await _forceRefreshMessages();
        emit(state.copyWith(
          isLoading: false,
          timestamp: DateTime.now().millisecondsSinceEpoch
        ));
      } 
      else if (message is Map) {
        print("Mesaj Map formatında alındı: $message");
        
        String body = message['body']?.toString() ?? '';
        String address = message['address']?.toString() ?? '';
        int timestamp = (message['date'] is num) 
            ? (message['date'] as num).toInt() 
            : DateTime.now().millisecondsSinceEpoch;
        
        // Convert timestamp to DateTime object
        DateTime dateTime = DateTime.fromMillisecondsSinceEpoch(timestamp);
        
        // Create a temporary message to show immediately
        final tempMessage = MyMessage(
          name: address,
          lastMessage: body,
          address: address,
          date: dateTime, // Now using DateTime instead of int
          threadId: 0 // Temporary
        );
        
        // Update UI with the temporary message
        List<MyMessage> updatedMessages = List.from(state.myMessages);
        bool threadExists = false;
        
        // Check if thread exists
        for (int i = 0; i < updatedMessages.length; i++) {
          if (updatedMessages[i].address == address) {
            updatedMessages[i].lastMessage = body;
            updatedMessages[i].date = dateTime; // Using DateTime here too
            threadExists = true;
            break;
          }
        }
        
        // Add new thread if needed
        if (!threadExists) {
          updatedMessages.add(tempMessage);
        }
        
        // Sort by latest
        updatedMessages.sort((a, b) => b.date!.compareTo(a.date!));
        
        // Update UI immediately with our best guess
        emit(state.copyWith(
          myMessages: updatedMessages,
          timestamp: DateTime.now().millisecondsSinceEpoch
        ));
        
        // Now do a proper refresh to get accurate data
        await Future.delayed(Duration(milliseconds: 500)); 
        await _forceRefreshMessages();
      }
      
      // Ensure loading is finished
      emit(state.copyWith(
        isLoading: false,
        isInit: true,
        timestamp: DateTime.now().millisecondsSinceEpoch
      ));
    } catch (e) {
      print("SMS güncelleme hatası: $e");
      emit(state.copyWith(isLoading: false));
      
      // Always try to refresh on error
      _forceRefreshMessages();
    }
  }

  // Force a full refresh of messages
  Future<void> _forceRefreshMessages() async {
    _gettingMessages = false; // Reset flag to force refresh
    await getMessages();
    await getSpam();
    
    // Update active conversation if needed
    if (state.address != null) {
      await filterMessageForAdress(state.address!);
    }
  }

  // Listeyi zorla yenilemek için dışarıdan çağrılabilir metod
  Future<void> forceRefresh() async {
    // Loading durumunu güncelle
    emit(state.copyWith(isLoading: true));
    
    // Önbelleği temizle ve _gettingMessages bayrağını sıfırla, böylece tüm mesajları yeniden yükleriz
    _gettingMessages = false;
    
    // Tüm mesajları yeniden yükle
    await _forceRefreshMessages();
    
    // Loading durumunu kapat ve zaman damgasını güncelle
    emit(state.copyWith(
      isLoading: false,
      isInit: true,
      timestamp: DateTime.now().millisecondsSinceEpoch
    ));
  }

  Future<void> filterMessageForAdress(address) async {
    emit(state.copyWith(address: address));
    SmsQuery query = SmsQuery();
    
    try {
      print("Belirli numara için mesajlar alınıyor: $address");
      var messages = await query.querySms(
        address: address, 
        kinds: [SmsQueryKind.inbox, SmsQueryKind.sent]
      );
      
      // Tekrar eden mesajları önlemek için bir set kullanacağız
      final uniqueMessages = <String>{};
      final uniqueFilteredMessages = <SmsMessage>[];
      
      for (var message in messages) {
        // Mesaj içeriği ve tarihinden benzersiz bir anahtar oluştur
        final key = '${message.body}|${message.date?.millisecondsSinceEpoch}';
        
        // Bu mesajı daha önce eklemedik mi kontrol et
        if (!uniqueMessages.contains(key)) {
          uniqueMessages.add(key);
          uniqueFilteredMessages.add(message);
        }
      }
      
      print("Toplam mesaj sayısı: ${messages.length}, benzersiz mesaj sayısı: ${uniqueFilteredMessages.length}");
      
      // Tarih sıralamasını düzelt (en eskiden yeniye)
      uniqueFilteredMessages.sort((a, b) => a.date!.compareTo(b.date!));
      
      // State'i güncelle (tersine çevirerek en yeni mesajlar üstte olacak)
      emit(state.copyWith(filtingMessages: uniqueFilteredMessages.reversed.toList()));
    } catch (e) {
      print("Konuşma filtreleme hatası: $e");
      emit(state.copyWith(filtingMessages: []));
    }
  }

  Future<void> getMessages() async {
    // Prevent concurrent calls to getMessages
    if (_gettingMessages) {
      print("getMessages already in progress - skipping");
      return;
    }
    
    _gettingMessages = true;
    
    try {
      var fonksiyonBaslangic = DateTime.now();
      
      // Check if we have SMS permissions
      final Telephony telephony = Telephony.instance;
      final bool? permissionsGranted = await telephony.requestPhoneAndSmsPermissions;
      
      if (permissionsGranted != true) {
        print("SMS permissions not granted");
        emit(state.copyWith(myMessages: [], messages: []));
        return;
      }

      // Get all SMS messages
      print("Fetching all SMS messages...");
      var messages = await SmsQuery().getAllSms;
      print("Found ${messages.length} SMS messages");
      
      // Mesajları benzersiz threadId'ler ile grupla
      Map<int?, SmsMessage> threadMap = {};
      
      // Her thread için en son mesajı tut
      for (var message in messages) {
        // Null threadId'leri filtrele
        if (message.threadId == null) continue;
        
        // Bu thread için zaten bir mesaj var mı kontrol et
        if (!threadMap.containsKey(message.threadId) || 
            (message.date != null && threadMap[message.threadId]!.date != null && 
             message.date!.isAfter(threadMap[message.threadId]!.date!))) {
          threadMap[message.threadId] = message;
        }
      }
      
      // Benzersiz threadId'lerden MyMessage listesi oluştur
      List<MyMessage> list = threadMap.values.map((sms) => 
        MyMessage(
          name: sms.address,
          lastMessage: sms.body,
          address: sms.address,
          date: sms.date,
          threadId: sms.threadId
        )
      ).toList();
      
      if (list.isEmpty) {
        emit(state.copyWith(myMessages: [], messages: messages));
        return;
      }

      // Get and match contacts
      try {
        final contacts = await _getContactsIfNeeded();
        if (contacts.isNotEmpty) {
          for (var element in contacts) {
            if (element.phones.isNotEmpty) {
              var phone = element.phones.first.number.toString().replaceAll(" ", "").replaceAll("-", "");
              for (var item in list) {
                if (item.address != null && item.address!.contains(phone)) {
                  item.name = element.displayName;
                }
              }
            }
          }
        } else {
          print("No contacts found");
        }
      } catch (e) {
        print("Error matching contacts: $e");
      }

      // Sort by latest message
      list.sort((a, b) => b.date!.compareTo(a.date!));
      
      // Update state with new messages
      emit(state.copyWith(
        myMessages: list, 
        messages: messages,
        timestamp: DateTime.now().millisecondsSinceEpoch
      ));
      
      print("Function completion time: ${DateTime.now().difference(fonksiyonBaslangic).inMilliseconds}ms");
      
    } catch (e) {
      print("Error in getMessages: $e");
      emit(state.copyWith(myMessages: [], messages: []));
    } finally {
      _gettingMessages = false;
    }
  }

  void onSearch(String search) {
    List<SearchSmsMessageModel> searchResult = [];
    if (search.isNotEmpty) {
      for (var element in state.messages) {
        for (var item in state.myMessages) {
          if (item.address == element.address) {
            if (item.name != null) {
              if (item.name!.toLowerCase().contains(search) ||
                  item.address!.toLowerCase().contains(search) ||
                  element.body!.toLowerCase().contains(search)) {
                searchResult.add(SearchSmsMessageModel(name: item.name, address: item.address, body: element.body, date: element.date));
              }
            } else {
              if (item.address!.toLowerCase().contains(search) || element.body!.toLowerCase().contains(search)) {
                searchResult.add(SearchSmsMessageModel(name: item.address, address: item.address, body: element.body, date: element.date));
              }
            }
          }
        }
      }
    }

    emit(state.copyWith(search: search, searchResult: searchResult));
  }

  void onClearSearch() {
    emit(state.copyWith(search: null, searchResult: []));
  }

  Future<void> getSpam() async {
    try {
      final Database db = await openDatabase(
        'SpamSMS',
        version: 1,
        onCreate: (Database db, int version) async {
          await db.execute(
              'CREATE TABLE Messages (id INTEGER PRIMARY KEY AUTOINCREMENT, address TEXT, message TEXT)');
        });
      
      final List<Map<String, dynamic>> results = await db.query('Messages');
      if (results.isNotEmpty) {
        emit(state.copyWith(spam: results.map((e) => Spam.fromJson(e)).toList().reversed.toList()));
      } else {
        emit(state.copyWith(spam: []));
      }
      await db.close();
    } catch (e) {
      print("Error in getSpam: $e");
      emit(state.copyWith(spam: []));
    }
  }

  void deleteSpam(Spam spam, context) async {
    showDialog(
        context: context,
        builder: (context) {
          return _alert(context, spam);
        });
  }

  _alert(BuildContext context, Spam spam) {
    return AlertDialog(
      title: const Text('Uyarı'),
      content: const Text('Bu mesajı silmek istediğinize emin misiniz?'),
      actions: [
        TextButton(
            onPressed: () {
              Navigator.pop(context);
            },
            child: const Text('İptal')),
        TextButton(
            onPressed: () async {
              Navigator.pop(context);
              final Database db = await openDatabase('SpamSMS');
              await db.delete('Messages', where: 'id = ?', whereArgs: [spam.id]);
              getSpam();
            },
            child: const Text('Sil')),
      ],
    );
  }
}
