package at.fedo.notes.controller;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.concurrent.Worker;
import javafx.css.PseudoClass;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.input.*;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import javafx.util.Duration;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

public class MainController {

    @FXML private TreeView<Path> noteTree;
    @FXML private WebView editorView;
    @FXML private ComboBox<String> headingCombo;

    private WebEngine webEngine;
    private Path currentFile = null;
    private boolean editorReady = false;
    private Runnable pendingEditorAction = null;
    private boolean updatingHeadingCombo = false;

    private static final Path NOTES_DIR = Paths.get(System.getProperty("user.home"), "Documents", "Notes");

    @FXML
    public void initialize() throws IOException {
        Files.createDirectories(NOTES_DIR);

        // WebView editor
        webEngine = editorView.getEngine();
        webEngine.getLoadWorker().stateProperty().addListener((obs, old, state) -> {
            if (state == Worker.State.SUCCEEDED) onEditorReady();
        });
        webEngine.loadContent(editorHtml());

        // Tree
        noteTree.setEditable(true);
        noteTree.setCellFactory(tv -> new RenamableTreeCell());
        loadTree();

        noteTree.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            saveNote(); // saves currentFile (the previously open note), not the newly-selected item
            if (newVal != null && Files.isRegularFile(newVal.getValue())) {
                openNote(newVal.getValue());
            }
            // Selecting a folder keeps the current note visible — don't clear the editor
        });

        noteTree.sceneProperty().addListener((obs, old, scene) -> {
            if (scene != null) {
                scene.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
                    if (e.isControlDown() && e.getCode() == KeyCode.S) {
                        saveNote();
                        e.consume();
                    }
                });
            }
        });

        Timeline autosave = new Timeline(new KeyFrame(Duration.seconds(10), e -> saveNote()));
        autosave.setCycleCount(Animation.INDEFINITE);
        autosave.play();

        // Heading combo
        headingCombo.getItems().addAll("Body Text", "H1", "H2", "H3");
        headingCombo.setValue("Body Text");

        // Tree context menu (empty space)
        MenuItem miNewNote   = new MenuItem("New Note");
        MenuItem miNewFolder = new MenuItem("New Folder");
        miNewNote.setOnAction(e   -> { try { newNote();   } catch (IOException ex) {} });
        miNewFolder.setOnAction(e -> { try { newFolder(); } catch (IOException ex) {} });
        noteTree.setContextMenu(new ContextMenu(miNewNote, miNewFolder));

        noteTree.setOnDragOver(e -> {
            if (e.getDragboard().hasString()) e.acceptTransferModes(TransferMode.MOVE);
            e.consume();
        });
        noteTree.setOnDragDropped(e -> {
            if (e.getDragboard().hasString())
                performMove(Paths.get(e.getDragboard().getString()), NOTES_DIR);
            e.setDropCompleted(true);
            e.consume();
        });
    }

    private void onEditorReady() {
        editorReady = true;
        // Poll for heading-level changes (300 ms is imperceptible)
        Timeline poller = new Timeline(new KeyFrame(Duration.millis(300), e -> {
            try {
                String level = (String) webEngine.executeScript("getCurrentHeadingLevel()");
                if (level != null && !level.equals(headingCombo.getValue())) {
                    updatingHeadingCombo = true;
                    headingCombo.setValue(level);
                    updatingHeadingCombo = false;
                }
            } catch (Exception ignored) {}
        }));
        poller.setCycleCount(Animation.INDEFINITE);
        poller.play();

        if (pendingEditorAction != null) {
            pendingEditorAction.run();
            pendingEditorAction = null;
        }
    }

    // ── Toolbar actions ──────────────────────────────────────────────────

    @FXML
    private void applyHeading() {
        if (updatingHeadingCombo || !editorReady) return;
        String value = headingCombo.getValue();
        if (value == null) return;
        int level = switch (value) { case "H1" -> 1; case "H2" -> 2; case "H3" -> 3; default -> 0; };
        webEngine.executeScript("applyHeadingLevel(" + level + ")");
    }

    @FXML
    private void toggleBulletList() {
        if (editorReady) webEngine.executeScript("applyListType('bullet')");
    }

    @FXML
    private void toggleTodoList() {
        if (editorReady) webEngine.executeScript("applyListType('todo')");
    }

    // ── File operations ──────────────────────────────────────────────────

    private void loadTree() throws IOException {
        TreeItem<Path> root = new TreeItem<>(NOTES_DIR);
        root.setExpanded(true);
        buildTree(root, NOTES_DIR);
        noteTree.setRoot(root);
        noteTree.setShowRoot(false);
    }

    private void buildTree(TreeItem<Path> parent, Path dir) throws IOException {
        List<Path> children;
        try (var stream = Files.list(dir)) {
            children = stream.sorted().collect(Collectors.toList());
        }
        for (Path path : children) {
            TreeItem<Path> item = new TreeItem<>(path);
            if (Files.isDirectory(path)) {
                item.setExpanded(true);
                buildTree(item, path);
                parent.getChildren().add(item);
            } else if (path.toString().endsWith(".txt")) {
                parent.getChildren().add(item);
            }
        }
    }

    private Path getTargetFolder() {
        TreeItem<Path> selected = noteTree.getSelectionModel().getSelectedItem();
        if (selected == null) return NOTES_DIR;
        Path p = selected.getValue();
        return Files.isDirectory(p) ? p : p.getParent();
    }

    @FXML
    private void newNote() throws IOException {
        Path folder = getTargetFolder();
        int i = 1;
        Path file;
        do { file = folder.resolve("Note " + i++ + ".txt"); } while (Files.exists(file));
        Files.writeString(file, "");
        loadTree();
        selectPath(file);
    }

    @FXML
    private void newFolder() throws IOException {
        Path folder = getTargetFolder();
        Path dir = folder.resolve("New Folder");
        int i = 1;
        while (Files.exists(dir)) dir = folder.resolve("New Folder " + i++);
        Files.createDirectory(dir);
        loadTree();
    }

    @FXML
    private void saveNote() {
        if (!editorReady || currentFile == null) return;
        try {
            String content = (String) webEngine.executeScript("getContent()");
            Files.writeString(currentFile, content);
        } catch (Exception ignored) {}
    }

    @FXML
    private void deleteNote() throws IOException {
        TreeItem<Path> selected = noteTree.getSelectionModel().getSelectedItem();
        if (selected == null) return;
        Path p = selected.getValue();
        if (Files.isDirectory(p)) {
            try (var stream = Files.walk(p)) {
                stream.sorted(Comparator.reverseOrder()).forEach(f -> {
                    try { Files.delete(f); } catch (IOException e) { throw new UncheckedIOException(e); }
                });
            }
        } else {
            Files.deleteIfExists(p);
        }
        clearEditor();
        loadTree();
    }

    private void openNote(Path file) {
        if (!editorReady) { pendingEditorAction = () -> loadIntoEditor(file); return; }
        loadIntoEditor(file);
    }

    private void loadIntoEditor(Path file) {
        try {
            // Normalise line endings, then escape for a JS single-quoted string literal.
            String content = Files.readString(file)
                    .replace("\r\n", "\n").replace("\r", "\n");
            String escaped = content
                    .replace("\\", "\\\\")
                    .replace("'",  "\\'")
                    .replace("\n", "\\n");
            webEngine.executeScript("setContent('" + escaped + "')");
            currentFile = file;
            setTitle(file.getFileName().toString().replace(".txt", ""));
        } catch (Exception e) {
            try { webEngine.executeScript("setContent('')"); } catch (Exception ignored) {}
        }
    }

    private void clearEditor() {
        if (!editorReady) { pendingEditorAction = null; return; }
        webEngine.executeScript("setContent('')");
        currentFile = null;
        setTitle("Notes");
    }

    private void setTitle(String text) {
        if (noteTree.getScene() == null) return;
        Stage stage = (Stage) noteTree.getScene().getWindow();
        if (stage != null) stage.setTitle(text);
    }

    private void selectPath(Path target) { selectInTree(noteTree.getRoot(), target); }

    private boolean selectInTree(TreeItem<Path> item, Path target) {
        if (item == null) return false;
        if (item.getValue().equals(target)) {
            noteTree.getSelectionModel().select(item);
            return true;
        }
        for (TreeItem<Path> child : item.getChildren()) {
            if (selectInTree(child, target)) return true;
        }
        return false;
    }

    private void performRename(Path oldPath, String newName) {
        try {
            String actualName = Files.isRegularFile(oldPath) ? newName + ".txt" : newName;
            Path newPath = oldPath.getParent().resolve(actualName);
            if (!newPath.equals(oldPath) && !Files.exists(newPath)) Files.move(oldPath, newPath);
            loadTree();
            selectPath(newPath);
        } catch (IOException ignored) {}
    }

    private Path computeTargetFolder(Path source, Path target) {
        Path folder = (target == null || !Files.exists(target))
                ? NOTES_DIR
                : Files.isDirectory(target) ? target : target.getParent();
        if (folder.startsWith(source)) return null;
        return folder;
    }

    private void performMove(Path source, Path targetFolder) {
        if (targetFolder == null || targetFolder.startsWith(source)) return;
        try {
            Path dest = targetFolder.resolve(source.getFileName());
            if (dest.equals(source) || Files.exists(dest)) return;
            Files.move(source, dest);
            loadTree();
            selectPath(dest);
        } catch (IOException ignored) {}
    }

    // ── Embedded HTML/CSS/JS editor ──────────────────────────────────────

    private static String editorHtml() {
        return """
            <!DOCTYPE html>
            <html>
            <head>
            <meta charset="UTF-8">
            <style>
            * { margin:0; padding:0; box-sizing:border-box; }
            html,body { height:100%; }
            body {
              background:#1e1e1e; color:#e0e0e0;
              font-family:Consolas,"Ubuntu Mono",monospace;
              padding:14px 18px; outline:none; cursor:text;
              caret-color:#e07820; min-height:100%;
            }
            div.line {
              white-space:pre-wrap; word-break:break-word;
              line-height:1.35; min-height:1.35em;
            }
            div.body   { font-size:26px; }
            div.bullet { font-size:26px; }
            div.todo   { font-size:26px; }
            div.h3     { font-size:32px; font-weight:bold; color:#e8e8e8; }
            div.h2     { font-size:38px; font-weight:bold; color:#f0f0f0; }
            div.h1     { font-size:46px; font-weight:bold; color:#ffffff; }
            ::selection { background:rgba(224,120,32,0.35); color:inherit; }
            ::-webkit-scrollbar { width:8px; }
            ::-webkit-scrollbar-track { background:#1a1a1a; }
            ::-webkit-scrollbar-thumb { background:#3a3a3a; }
            ::-webkit-scrollbar-thumb:hover { background:#e07820; }
            </style>
            </head>
            <body id="editor" contenteditable="true" spellcheck="false"></body>
            <script>
            var ed = document.getElementById('editor');

            function cls(t) {
              if (t.startsWith('### ')) return 'h3';
              if (t.startsWith('## '))  return 'h2';
              if (t.startsWith('# '))   return 'h1';
              if (t.startsWith('\\u2022 ')) return 'bullet';
              if (t.startsWith('\\u2610 ') || t.startsWith('\\u2611 ')) return 'todo';
              return 'body';
            }

            function strip(t) {
              if (t.startsWith('### ')) return t.slice(4);
              if (t.startsWith('## '))  return t.slice(3);
              if (t.startsWith('# '))   return t.slice(2);
              if (t.startsWith('\\u2022 ') || t.startsWith('\\u2610 ') || t.startsWith('\\u2611 ')) return t.slice(2);
              return t;
            }

            function listPfx(t) {
              if (t.startsWith('\\u2022 ')) return '\\u2022 ';
              if (t.startsWith('\\u2610 ')) return '\\u2610 ';
              if (t.startsWith('\\u2611 ')) return '\\u2610 ';
              return '';
            }

            function lineDiv(n) {
              if (!n) return null;
              if (n.nodeType === 3) n = n.parentElement;
              while (n && n.parentElement !== ed) n = n.parentElement;
              return (n && n.parentElement === ed) ? n : null;
            }

            function offIn(range, el) {
              try {
                var t = document.createRange();
                t.setStart(el, 0);
                t.setEnd(range.startContainer, range.startOffset);
                return t.toString().length;
              } catch(e) { return 0; }
            }

            function setCaret(el, pos) {
              var r = document.createRange();
              var n = el.firstChild;
              if (!n || n.nodeName === 'BR') r.setStart(el, 0);
              else if (n.nodeType === 3) r.setStart(n, Math.min(pos, n.length));
              else r.setStart(el, 0);
              r.collapse(true);
              var s = window.getSelection(); s.removeAllRanges(); s.addRange(r);
            }

            function mkLine(text) {
              var d = document.createElement('div');
              d.className = 'line ' + cls(text);
              d.textContent = text;
              if (!d.textContent) d.innerHTML = '<br>';
              return d;
            }

            function setContent(text) {
              ed.innerHTML = '';
              (text || '').split('\\n').forEach(function(l) { ed.appendChild(mkLine(l)); });
              if (!ed.children.length) ed.appendChild(mkLine(''));
            }

            function getContent() {
              var lines = Array.from(ed.children).map(function(d) { return d.textContent; });
              while (lines.length > 1 && lines[lines.length - 1] === '') lines.pop();
              return lines.join('\\n');
            }

            function getCurrentHeadingLevel() {
              var s = window.getSelection();
              if (!s || !s.rangeCount) return 'Body Text';
              var el = lineDiv(s.focusNode);
              if (!el) return 'Body Text';
              var t = el.textContent;
              if (t.startsWith('### ')) return 'H3';
              if (t.startsWith('## '))  return 'H2';
              if (t.startsWith('# '))   return 'H1';
              return 'Body Text';
            }

            function applyHeadingLevel(level) {
              applyPrefix(['', '# ', '## ', '### '][level] || '', false);
            }

            function applyListType(type) {
              applyPrefix(type === 'bullet' ? '\\u2022 ' : '\\u2610 ', true);
            }

            function applyPrefix(prefix, toggle) {
              var s = window.getSelection();
              if (!s || !s.rangeCount) return;
              var r = s.getRangeAt(0);
              var sl = lineDiv(r.startContainer), el = lineDiv(r.endContainer);
              var inRange = false, lines = [];
              Array.from(ed.children).forEach(function(c) {
                if (c === sl) inRange = true;
                if (inRange) lines.push(c);
                if (c === el) inRange = false;
              });
              if (!lines.length) return;
              var rm = toggle && lines.every(function(l) { return l.textContent.startsWith(prefix); });
              lines.forEach(function(l) {
                var t = rm ? strip(l.textContent) : prefix + strip(l.textContent);
                l.textContent = t;
                if (!l.textContent) l.innerHTML = '<br>';
                l.className = 'line ' + cls(l.textContent);
              });
              // Restore cursor so subsequent Enter/typing still sees the right line
              if (lines.length) setCaret(lines[0], lines[0].textContent.length);
            }

            ed.addEventListener('keydown', function(e) {
              var s = window.getSelection();
              if (!s || !s.rangeCount) return;
              var range = s.getRangeAt(0);

              if (e.key === 'Enter' || e.keyCode === 13) {
                e.preventDefault();
                if (!range.collapsed) range.deleteContents();
                var el = lineDiv(range.startContainer); if (!el) return;
                var text = el.textContent, off = offIn(range, el), lp = listPfx(text);
                // Enter on an empty list item exits the list
                if (lp && text.trim() === lp.trim()) {
                  el.textContent = ''; el.innerHTML = '<br>'; el.className = 'line body';
                  setCaret(el, 0); return;
                }
                var before = text.slice(0, off), after = text.slice(off);
                el.textContent = before;
                if (!el.textContent) el.innerHTML = '<br>';
                el.className = 'line ' + cls(before);
                var np = (lp && off >= lp.length) ? lp : '';
                var nl = mkLine(np + after);
                el.nextSibling ? ed.insertBefore(nl, el.nextSibling) : ed.appendChild(nl);
                setCaret(nl, np.length);
                return;
              }

              if ((e.key === 'Backspace' || e.keyCode === 8) && range.collapsed) {
                var el = lineDiv(range.startContainer); if (!el) return;
                if (offIn(range, el) === 0 && el.previousSibling) {
                  e.preventDefault();
                  var prev = el.previousSibling;
                  var pt = prev.textContent, ct = el.textContent;
                  var merged = pt + ct;
                  prev.textContent = merged;
                  if (!prev.textContent) prev.innerHTML = '<br>';
                  prev.className = 'line ' + cls(prev.textContent);
                  ed.removeChild(el);
                  setCaret(prev, pt.length);
                }
                return;
              }

              if ((e.key === 'Delete' || e.keyCode === 46) && range.collapsed) {
                var el = lineDiv(range.startContainer); if (!el) return;
                var text = el.textContent, off = offIn(range, el);
                if (off === text.length && el.nextSibling) {
                  e.preventDefault();
                  var next = el.nextSibling, merged = text + next.textContent;
                  el.textContent = merged;
                  if (!el.textContent) el.innerHTML = '<br>';
                  el.className = 'line ' + cls(el.textContent);
                  ed.removeChild(next);
                  setCaret(el, text.length);
                }
              }
            });

            // Click near start of a to-do line to toggle ☐/☑
            ed.addEventListener('mousedown', function() {
              setTimeout(function() {
                var s = window.getSelection(); if (!s || !s.rangeCount) return;
                var r = s.getRangeAt(0), el = lineDiv(r.startContainer); if (!el) return;
                var t = el.textContent, off = offIn(r, el);
                if (off <= 1) {
                  if (t.charAt(0) === '\\u2610') el.textContent = '\\u2611' + t.slice(1);
                  else if (t.charAt(0) === '\\u2611') el.textContent = '\\u2610' + t.slice(1);
                }
              }, 0);
            });

            // Plain-text paste
            ed.addEventListener('paste', function(e) {
              e.preventDefault();
              var text = e.clipboardData.getData('text/plain');
              var s = window.getSelection(); if (!s || !s.rangeCount) return;
              var range = s.getRangeAt(0);
              if (!range.collapsed) range.deleteContents();
              var el = lineDiv(range.startContainer); if (!el) return;
              var off = offIn(range, el), cur = el.textContent;
              var pl = text.split('\\n');
              if (pl.length === 1) {
                var nt = cur.slice(0, off) + text + cur.slice(off);
                el.textContent = nt; if (!el.textContent) el.innerHTML = '<br>';
                el.className = 'line ' + cls(el.textContent);
                setCaret(el, off + text.length);
              } else {
                var before = cur.slice(0, off), after = cur.slice(off);
                el.textContent = before + pl[0]; if (!el.textContent) el.innerHTML = '<br>';
                el.className = 'line ' + cls(el.textContent);
                var last = el;
                for (var i = 1; i < pl.length; i++) {
                  var t2 = i === pl.length - 1 ? pl[i] + after : pl[i];
                  var nl = mkLine(t2);
                  last.nextSibling ? ed.insertBefore(nl, last.nextSibling) : ed.appendChild(nl);
                  last = nl;
                }
                setCaret(last, pl[pl.length - 1].length);
              }
            });

            // Update CSS class as user types
            ed.addEventListener('input', function() {
              var s = window.getSelection(); if (!s || !s.rangeCount) return;
              var el = lineDiv(s.focusNode);
              if (el) el.className = 'line ' + cls(el.textContent);
            });
            </script>
            </html>
            """;
    }

    // ── TreeCell (rename + drag-and-drop) ────────────────────────────────

    private class RenamableTreeCell extends TreeCell<Path> {
        private static final PseudoClass DRAG_OVER_PC = PseudoClass.getPseudoClass("drag-over");

        private TextField textField;
        private final ContextMenu cellMenu;

        RenamableTreeCell() {
            setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !isEmpty()) getTreeView().edit(getTreeItem());
            });

            MenuItem miRename = new MenuItem("Rename");
            MenuItem miDelete = new MenuItem("Delete");
            miRename.setOnAction(e -> getTreeView().edit(getTreeItem()));
            miDelete.setOnAction(e -> { try { deleteNote(); } catch (IOException ex) {} });
            cellMenu = new ContextMenu(miRename, miDelete);
            cellMenu.setOnShowing(e -> noteTree.getSelectionModel().select(getTreeItem()));

            setOnDragDetected(e -> {
                if (isEmpty()) return;
                Dragboard db = startDragAndDrop(TransferMode.MOVE);
                ClipboardContent cc = new ClipboardContent();
                cc.putString(getItem().toString());
                db.setContent(cc);
                e.consume();
            });

            setOnDragOver(e -> {
                if (e.getGestureSource() != this && e.getDragboard().hasString()) {
                    Path src = Paths.get(e.getDragboard().getString());
                    if (computeTargetFolder(src, isEmpty() ? null : getItem()) != null)
                        e.acceptTransferModes(TransferMode.MOVE);
                }
                e.consume();
            });

            setOnDragEntered(e -> {
                if (e.getGestureSource() != this && e.getDragboard().hasString())
                    pseudoClassStateChanged(DRAG_OVER_PC, true);
                e.consume();
            });

            setOnDragExited(e -> {
                pseudoClassStateChanged(DRAG_OVER_PC, false);
                e.consume();
            });

            setOnDragDropped(e -> {
                boolean ok = false;
                if (e.getDragboard().hasString()) {
                    Path src = Paths.get(e.getDragboard().getString());
                    Path folder = computeTargetFolder(src, isEmpty() ? null : getItem());
                    if (folder != null) { performMove(src, folder); ok = true; }
                }
                e.setDropCompleted(ok);
                e.consume();
            });
        }

        @Override
        protected void updateItem(Path item, boolean empty) {
            super.updateItem(item, empty);
            setContextMenu(empty || item == null ? null : cellMenu);
            if (empty || item == null) {
                setText(null); setGraphic(null);
            } else if (isEditing()) {
                if (textField != null) textField.setText(displayName(item));
                setText(null); setGraphic(textField);
            } else {
                setText(displayName(item)); setGraphic(null);
            }
        }

        @Override
        public void startEdit() {
            super.startEdit();
            if (textField == null) {
                textField = new TextField();
                textField.setOnAction(e -> commitRename(textField.getText().trim()));
                textField.setOnKeyPressed(e -> { if (e.getCode() == KeyCode.ESCAPE) cancelEdit(); });
                textField.focusedProperty().addListener((obs, was, now) -> {
                    if (!now && isEditing()) commitRename(textField.getText().trim());
                });
            }
            textField.setText(displayName(getItem()));
            setText(null); setGraphic(textField);
            textField.selectAll(); textField.requestFocus();
        }

        @Override
        public void cancelEdit() {
            super.cancelEdit();
            setText(displayName(getItem())); setGraphic(null);
        }

        private void commitRename(String newName) {
            if (!isEditing()) return;
            Path oldPath = getItem();
            cancelEdit();
            if (!newName.isEmpty()) performRename(oldPath, newName);
        }

        private String displayName(Path path) {
            if (path == null) return "";
            String name = path.getFileName().toString();
            return name.endsWith(".txt") ? name.replace(".txt", "") : name;
        }
    }
}
