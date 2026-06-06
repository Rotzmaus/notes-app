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
  main.fxml                       # BorderPane: ToolBar (top), TreeView (left), TextArea (center)
```

## How it works

- Notes are saved as `.txt` files under `~/Documents/Notes/`
- Folders in the app = real subdirectories on disk
- The left panel is a `TreeView<Path>` — folders are directories, notes are `.txt` leaf nodes
- Selecting a note loads it into the editor; selecting a folder clears the editor
- "New Note" creates a note inside the currently selected folder (or root if none selected)
- "New Folder" creates a subfolder inside the currently selected folder
- "Delete" removes a note file, or a folder and all its contents recursively
- `.txt` extension is stripped from display names via a custom `TreeCell`

## Key details

- `getTargetFolder()` resolves where to create: selected dir → use it; selected note → use its parent; nothing selected → `NOTES_DIR`
- Tree is rebuilt from disk on every mutating action (`loadTree()`)
- `selectPath()` walks the tree after creation to restore selection
- No database — filesystem is the source of truth
