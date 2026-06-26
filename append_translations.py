import sys

def parse_translations(file_path):
    with open(file_path, 'r', encoding='utf-8') as f:
        content = f.read()

    new_translations = {
        'English (en)': '  "action_minimize": "Minimize",\n  "discoverable_to_other_devices": "Discoverable to other devices",\n  "no_files_selected_hint": "No files selected. Browse to add files.",\n  "no_paired_devices_found_hint": "No paired devices found."\n',
        'Simplified Chinese (zh-CN)': '  "action_minimize": "最小化",\n  "discoverable_to_other_devices": "可被其他设备发现",\n  "no_files_selected_hint": "未选择任何文件。请浏览以添加文件。",\n  "no_paired_devices_found_hint": "未找到已配对的设备。"\n',
        'Traditional Chinese (zh-TW)': '  "action_minimize": "最小化",\n  "discoverable_to_other_devices": "可被其他裝置發現",\n  "no_files_selected_hint": "未選擇任何檔案。請瀏覽以新增檔案。",\n  "no_paired_devices_found_hint": "未找到已配對的裝置。"\n',
        'Spanish (es)': '  "action_minimize": "Minimizar",\n  "discoverable_to_other_devices": "Visible para otros dispositivos",\n  "no_files_selected_hint": "No hay archivos seleccionados. Explora para añadir archivos.",\n  "no_paired_devices_found_hint": "No se encontraron dispositivos emparejados."\n',
        'French (fr)': '  "action_minimize": "Réduire",\n  "discoverable_to_other_devices": "Visible par les autres appareils",\n  "no_files_selected_hint": "Aucun fichier sélectionné. Parcourez pour ajouter des fichiers.",\n  "no_paired_devices_found_hint": "Aucun appareil couplé trouvé."\n',
        'Japanese (ja)': '  "action_minimize": "最小化",\n  "discoverable_to_other_devices": "他のデバイスから検出可能",\n  "no_files_selected_hint": "ファイルが選択されていません。参照してファイルを追加してください。",\n  "no_paired_devices_found_hint": "ペアリングされたデバイスが見つかりません。"\n',
        'German (de)': '  "action_minimize": "Minimieren",\n  "discoverable_to_other_devices": "Für andere Geräte sichtbar",\n  "no_files_selected_hint": "Keine Dateien ausgewählt. Durchsuchen, um Dateien hinzuzufügen.",\n  "no_paired_devices_found_hint": "Keine gekoppelten Geräte gefunden."\n',
        'Portuguese (pt)': '  "action_minimize": "Minimizar",\n  "discoverable_to_other_devices": "Visível para outros dispositivos",\n  "no_files_selected_hint": "Nenhum arquivo selecionado. Navegue para adicionar arquivos.",\n  "no_paired_devices_found_hint": "Nenhum dispositivo emparelhado encontrado."\n',
        'Indonesian (id)': '  "action_minimize": "Minimalkan",\n  "discoverable_to_other_devices": "Dapat ditemukan oleh perangkat lain",\n  "no_files_selected_hint": "Tidak ada file yang dipilih. Telusuri untuk menambahkan file.",\n  "no_paired_devices_found_hint": "Tidak ada perangkat tersanding yang ditemukan."\n',
        'Russian (ru)': '  "action_minimize": "Свернуть",\n  "discoverable_to_other_devices": "Доступно для обнаружения другими устройствами",\n  "no_files_selected_hint": "Файлы не выбраны. Откройте обзор, чтобы добавить файлы.",\n  "no_paired_devices_found_hint": "Сопряженные устройства не найдены."\n'
    }

    for lang, new_text in new_translations.items():
        start_idx = content.find(lang)
        if start_idx == -1:
            print(f'Warning: {lang} not found.')
            continue
            
        next_brace_idx = content.find('}', start_idx)
        
        if next_brace_idx != -1:
            last_char_idx = next_brace_idx - 1
            while last_char_idx > start_idx and content[last_char_idx].isspace():
                last_char_idx -= 1
                
            before = content[:last_char_idx + 1]
            after = content[next_brace_idx:]
            
            if not before.endswith(','):
                before += ','
            
            before += '\n' + new_text
            content = before + after
            
    with open(file_path, 'w', encoding='utf-8') as f:
        f.write(content)
        
    print('Translations appended successfully!')

parse_translations('translation.json.txt')
