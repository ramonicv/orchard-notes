# Real note payloads

`*.b64` files are real `TextDataEncrypted` values (base64 of the compressed
protobuf, exactly as CloudKit returns them) captured from test notes in the
icloud-md author's own account. They come from
[icloud-md](https://github.com/coddingtonbear/icloud-md)
(`src/notes/realFixtures.ts`, MIT License, Copyright (c) 2026 Adam Coddington).

- `real_plain_note.b64`: plain text, single paragraph style.
- `real_formatted_multi_edit_note.b64`: 58 attribute runs (bold, underline,
  strikethrough, highlight, attachments) and 75 substrings across 6 replicas,
  21 of them tombstoned. One substring carries two child edges.
- `real_unicode_note.b64`: Spanish text plus an emoji (UTF-8 vs UTF-16 boundaries).
- `real_first_save_note.b64`: a brand-new note's first save from the web client.
