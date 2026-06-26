# -*- coding: utf-8 -*-
import json
import os

translation_file = "src/main/resources/translation.json.txt"

with open(translation_file, 'r', encoding='utf-8') as f:
    lines = f.readlines()

new_content = []
current_lang = None

lang_map = {
    'Simplified Chinese (zh-CN)': {
        "history_and_push_title": "剪贴板与推送",
        "nav_pairing": "配对",
        "nav_clipboard": "剪贴板",
        "nav_android": "Android",
        "nav_settings": "设置",
        "label_from": "来自: ",
        "label_files": "个文件",
        "requesting_pair_title": "正在请求配对",
        "incoming_files_title": "传入文件"
    },
    'Traditional Chinese (zh-TW)': {
        "history_and_push_title": "剪貼簿與推送",
        "nav_pairing": "配對",
        "nav_clipboard": "剪貼簿",
        "nav_android": "Android",
        "nav_settings": "設定",
        "label_from": "來自: ",
        "label_files": "個檔案",
        "requesting_pair_title": "正在請求配對",
        "incoming_files_title": "傳入檔案"
    },
    'German (de)': {
        "history_and_push_title": "Zwischenablage & Senden",
        "nav_pairing": "Kopplung",
        "nav_clipboard": "Zwischenablage",
        "nav_android": "Android",
        "nav_settings": "Einstellungen",
        "label_from": "Von: ",
        "label_files": "Datei(en)",
        "requesting_pair_title": "Kopplung wird angefordert",
        "incoming_files_title": "Eingehende Datei(en)"
    },
    'Japanese (ja)': {
        "history_and_push_title": "クリップボードとプッシュ",
        "nav_pairing": "ペアリング",
        "nav_clipboard": "クリップボード",
        "nav_android": "Android",
        "nav_settings": "設定",
        "label_from": "送信元: ",
        "label_files": "個のファイル",
        "requesting_pair_title": "ペアリングをリクエスト中",
        "incoming_files_title": "着信ファイル"
    },
    'Spanish (es)': {
        "history_and_push_title": "Portapapeles y Enviar",
        "nav_pairing": "Emparejamiento",
        "nav_clipboard": "Portapapeles",
        "nav_android": "Android",
        "nav_settings": "Ajustes",
        "label_from": "De: ",
        "label_files": "archivo(s)",
        "requesting_pair_title": "Solicitando Emparejamiento",
        "incoming_files_title": "Archivo(s) Entrante(s)"
    },
    'French (fr)': {
        "history_and_push_title": "Presse-papiers & Envoi",
        "nav_pairing": "Couplage",
        "nav_clipboard": "Presse-papiers",
        "nav_android": "Android",
        "nav_settings": "Paramètres",
        "label_from": "De : ",
        "label_files": "fichier(s)",
        "requesting_pair_title": "Demande de couplage",
        "incoming_files_title": "Fichier(s) entrant(s)"
    },
    'Portuguese (pt)': {
        "history_and_push_title": "Área de Transferência e Envio",
        "nav_pairing": "Emparelhamento",
        "nav_clipboard": "Área de Transferência",
        "nav_android": "Android",
        "nav_settings": "Configurações",
        "label_from": "De: ",
        "label_files": "arquivo(s)",
        "requesting_pair_title": "Solicitando Emparelhamento",
        "incoming_files_title": "Arquivo(s) Recebido(s)"
    },
    'Indonesian (id)': {
        "history_and_push_title": "Papan Klip & Kirim",
        "nav_pairing": "Pemasangan",
        "nav_clipboard": "Papan Klip",
        "nav_android": "Android",
        "nav_settings": "Pengaturan",
        "label_from": "Dari: ",
        "label_files": "file",
        "requesting_pair_title": "Meminta Pemasangan",
        "incoming_files_title": "File Masuk"
    },
    'Russian (ru)': {
        "history_and_push_title": "Буфер обмена и Отправка",
        "nav_pairing": "Сопряжение",
        "nav_clipboard": "Буфер обмена",
        "nav_android": "Android",
        "nav_settings": "Настройки",
        "label_from": "От: ",
        "label_files": "файл(ов)",
        "requesting_pair_title": "Запрос на сопряжение",
        "incoming_files_title": "Входящие файлы"
    }
}

inserted_keys = set()
for line in lines:
    stripped = line.strip()
    
    if stripped in lang_map.keys() or stripped == "English (en)":
        current_lang = stripped
        inserted_keys = set()
        
    if current_lang in lang_map and '"settings_title":' in line:
        for key, value in lang_map[current_lang].items():
            if key not in inserted_keys:
                if key != "history_and_push_title":
                    new_content.append(f'  "{key}": "{value}",\n')
                inserted_keys.add(key)

    if current_lang in lang_map and '"history_and_push_title":' in line:
        val = lang_map[current_lang]["history_and_push_title"]
        new_content.append(f'  "history_and_push_title": "{val}",\n')
        inserted_keys.add("history_and_push_title")
        continue

    new_content.append(line)

with open(translation_file, 'w', encoding='utf-8') as f:
    f.writelines(new_content)

print("Updated translation.json.txt")
