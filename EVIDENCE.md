# Como a busca funciona no ColorNote (evidências)

Fonte: `colornote-notepad-4-9-2.apk` (sha256 `701d9588f234a95eb96a2fbb133124bec24184af7766bffa6ac77ae741f86f1b`),
inspecionado offline extraindo `classes.dex` e `AndroidManifest.xml` do APK e lendo as tabelas de strings
(decompilação completa não disponível neste ambiente — sem jadx/apktool instalados — então a evidência é
baseada nos nomes de classes e nas strings literais do bytecode, não no código-fonte reconstruído).

## 1. Tela de busca dedicada
`classes.dex` lista a classe `com.socialnmobile.colornote.activity.Search` entre as activities do app
(junto de `Main`, `NoteEditor`, `Archive`, etc). É uma Activity própria, não um simples filtro na lista.

## 2. Integração com o sistema Android
No `AndroidManifest.xml` (decodificado do formato binário AXML), a Search activity declara:
- `<meta-data android:name="android.app.searchable" .../>` — registra a activity como "searchable" do Android.
- `<intent-filter><action android:name="android.intent.action.SEARCH"/></intent-filter>` — recebe buscas
  vindas da Quick Search Box / busca por voz do sistema.
- Uma ação própria, `note.socialnmobile.intent.action.SEARCH`, usada internamente para abrir a busca a
  partir de outras telas do próprio app, sem passar pelo mecanismo do sistema.
- A `Main` activity aponta para `Search` via `android.app.default_searchable`.

## 3. Mecanismo de filtro: LIKE, não full-text search
As strings do `classes.dex` mostram o modelo de dados local (SQLite):
- Tabela `notes`, com colunas reais: `title`, `folder_id`, `active_state`, `account_id`, `space`, `type`,
  `status`, `color_index` (ex.: `ALTER TABLE notes ADD COLUMN folder_id INTEGER DEFAULT 0;`).
- Índice `idx_note1 ON notes(active_state,account_id,folder_id,space)` — não há índice sobre o texto
  pesquisável (título/corpo), o que indica varredura linear no texto, não busca indexada.
- O padrão de query usado para substring é `... LIKE ? ESCAPE '\'` — um `LIKE` parametrizado com `ESCAPE`
  para tratar literalmente `%`/`_` digitados pelo usuário.
- Não há nenhuma string `fts3`/`fts4`/`MATCH`/"virtual table" associada à tabela de notas — ou seja,
  **não é full-text search**, é um `LIKE '%termo%'` simples sobre os campos de texto.

## Resumo da arquitetura replicada
Search activity própria → digita na caixa de busca → debounce → `SELECT * FROM notes WHERE active_state = 0
AND (title LIKE '%termo%' ESCAPE '\' OR body LIKE '%termo%' ESCAPE '\') ORDER BY modifiedDate DESC`.

## Implementação equivalente neste projeto
Ver `app/src/main/java/com/example/notepad/`:
- `data/Note.kt`, `data/NoteDao.kt`, `data/NoteRepository.kt` — mesmo modelo (`notes`, `title`, `body`,
  `activeState`) e a mesma técnica `LIKE ... ESCAPE '\'` com escape de `%`, `_` e `\` feito em
  `NoteRepository.escapeLikePattern`.
- `ui/SearchActivity.kt` + `ui/SearchViewModel.kt` — tela de busca com debounce (300ms) e resultado
  reativo via `LiveData`, com destaque (highlight) do termo encontrado em `ui/NoteAdapter.kt`.
- `AndroidManifest.xml` — replica a mesma integração: `SearchActivity` como searchable do sistema
  (`android.intent.action.SEARCH`) e uma ação interna própria (`com.example.notepad.action.SEARCH`).

Este projeto não foi compilado neste ambiente (não há Android SDK/emulador aqui). Abra a pasta no
Android Studio para sincronizar o Gradle e rodar.
