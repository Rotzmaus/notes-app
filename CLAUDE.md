# Notes App

JavaFX desktop notes app (Maven, Java 21). Stores notes as `.txt` files on disk.

## Run

```
mvn javafx:run
```

Or use the desktop shortcut at `~/Desktop/Notes.desktop`.

## Structure

```
src/main/java/at/fedo/notes/
  App.java                        # Entry point, loads main.fxml into a 900x600 stage
  controller/MainController.java  # All UI logic

src/main/resources/at/fedo/notes/
  main.fxml                       # BorderPane: VBox left (file-toolbar + TreeView), VBox center (formatting-toolbar + TextArea)
  styles.css                      # Dark theme — see Appearance section
```

## How it works

- Notes are saved as `.txt` files under `~/Documents/Notes/`
- Folders in the app = real subdirectories on disk
- **Layout**: `BorderPane` — left `VBox` holds the file toolbar + `TreeView`; center `VBox` holds the formatting toolbar (empty placeholder) + `TextArea`
- The left panel is a `TreeView<Path>` — folders are directories, notes are `.txt` leaf nodes
- Selecting a note loads it into the editor; selecting a folder clears the editor
- `.txt` extension is stripped from display names via a custom `TreeCell` (`RenamableTreeCell`)

## Toolbar & interactions

- **Left toolbar**: "New Note" and "New Folder" only — creates inside selected folder/root
- **Right-click on a note or folder**: context menu with Rename and Delete
- **Right-click on empty tree space**: context menu with New Note and New Folder
- **Double-click a tree item**: inline rename via `TextField` in the cell (Enter confirms, Escape cancels)
- **Drag a note or folder** onto a folder → moves inside it; onto a note/empty space → moves to that level; circular moves are blocked
- **Ctrl+S**: saves the current note (scene-level event filter, works regardless of focus)
- **Autosave**: `Timeline` fires every 10 seconds and saves the open note silently

## Appearance

- **Theme**: dark — `#1a1a1a` window background, `#1e1e1e` panels, `#252525` toolbar
- **Accent colour**: orange `#e07820` — used for button hover/press, selected tree items, focus borders, scrollbar thumb hover
- **Shape language**: rectangular and sharp — `border-radius: 0` everywhere, no rounded corners
- **Stylesheet**: `src/main/resources/at/fedo/notes/styles.css`, loaded in `App.java` via `scene.getStylesheets()`
- **Text colours**: UI labels `#cccccc`, editor text `#e0e0e0`, selected-item text `#111111` (dark for contrast on orange)
- **Fonts**: UI — system sans-serif; editor — monospace (Consolas / Ubuntu Mono)

Any future UI work must stay consistent with these choices: dark backgrounds, orange as the sole accent, zero border-radius on all controls.

## Key details

- `getTargetFolder()` resolves where to create: selected dir → use it; selected note → use its parent; nothing selected → `NOTES_DIR`
- Tree is rebuilt from disk on every mutating action (`loadTree()`)
- `selectPath()` walks the tree after creation to restore selection
- `performRename()` moves the file on disk (re-appends `.txt` for notes); `performMove()` moves notes/folders between directories
- `computeTargetFolder()` resolves the drop destination and blocks circular moves
- Context menus use `setContextMenu()` on both the tree and each cell — JavaFX owns the show/hide lifecycle so auto-hide works correctly; empty cells expose the tree's menu, cells with content show the item menu
- No database — filesystem is the source of truth
