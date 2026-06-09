package at.fedo.notes.controller;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.css.PseudoClass;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.input.*;
import javafx.util.Duration;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

public class MainController {

    @FXML private TreeView<Path> noteTree;
    @FXML private TextArea editor;

    private static final Path NOTES_DIR = Paths.get(System.getProperty("user.home"), "Documents", "Notes");

    @FXML
    public void initialize() throws IOException {
        Files.createDirectories(NOTES_DIR);

        noteTree.setEditable(true);
        noteTree.setCellFactory(tv -> new RenamableTreeCell());

        loadTree();

        noteTree.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null && Files.isRegularFile(newVal.getValue())) {
                openNote(newVal.getValue());
            } else {
                editor.clear();
            }
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

        // Right-click on empty tree space → New Note / New Folder
        MenuItem miNewNote   = new MenuItem("New Note");
        MenuItem miNewFolder = new MenuItem("New Folder");
        miNewNote.setOnAction(e   -> { try { newNote();   } catch (IOException ex) {} });
        miNewFolder.setOnAction(e -> { try { newFolder(); } catch (IOException ex) {} });
        ContextMenu emptyMenu = new ContextMenu(miNewNote, miNewFolder);
        noteTree.setOnContextMenuRequested(e -> {
            emptyMenu.show(noteTree, e.getScreenX(), e.getScreenY());
            e.consume();
        });

        // Drag over/drop on the tree background → move to root
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
        TreeItem<Path> selected = noteTree.getSelectionModel().getSelectedItem();
        if (selected == null || !Files.isRegularFile(selected.getValue())) return;
        try { Files.writeString(selected.getValue(), editor.getText()); } catch (IOException ignored) {}
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
        editor.clear();
        loadTree();
    }

    private void openNote(Path file) {
        try { editor.setText(Files.readString(file)); } catch (IOException e) { editor.setText(""); }
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

    // Returns the folder to drop `source` into when dropped on `target`.
    // Returns null if the move would be circular or a no-op.
    private Path computeTargetFolder(Path source, Path target) {
        Path folder = (target == null || !Files.exists(target))
                ? NOTES_DIR
                : Files.isDirectory(target) ? target : target.getParent();
        if (folder.startsWith(source)) return null; // circular
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

    private class RenamableTreeCell extends TreeCell<Path> {
        private static final PseudoClass DRAG_OVER_PC = PseudoClass.getPseudoClass("drag-over");

        private TextField textField;

        RenamableTreeCell() {
            // ── Double-click → rename ──────────────────────────────────────
            setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !isEmpty()) getTreeView().edit(getTreeItem());
            });

            // ── Right-click on item → Rename / Delete ─────────────────────
            MenuItem miRename = new MenuItem("Rename");
            MenuItem miDelete = new MenuItem("Delete");
            miRename.setOnAction(e -> getTreeView().edit(getTreeItem()));
            miDelete.setOnAction(e -> { try { deleteNote(); } catch (IOException ex) {} });
            ContextMenu cellMenu = new ContextMenu(miRename, miDelete);

            setOnContextMenuRequested(e -> {
                if (!isEmpty()) {
                    noteTree.getSelectionModel().select(getTreeItem());
                    cellMenu.show(this, e.getScreenX(), e.getScreenY());
                    e.consume(); // stop emptyMenu on the tree from firing
                }
            });

            // ── Drag source ───────────────────────────────────────────────
            setOnDragDetected(e -> {
                if (isEmpty()) return;
                Dragboard db = startDragAndDrop(TransferMode.MOVE);
                ClipboardContent cc = new ClipboardContent();
                cc.putString(getItem().toString());
                db.setContent(cc);
                e.consume();
            });

            // ── Drag target — accept ──────────────────────────────────────
            setOnDragOver(e -> {
                if (e.getGestureSource() != this && e.getDragboard().hasString()) {
                    Path src = Paths.get(e.getDragboard().getString());
                    if (computeTargetFolder(src, isEmpty() ? null : getItem()) != null)
                        e.acceptTransferModes(TransferMode.MOVE);
                }
                e.consume();
            });

            // ── Drag target — highlight ───────────────────────────────────
            setOnDragEntered(e -> {
                if (e.getGestureSource() != this && e.getDragboard().hasString())
                    pseudoClassStateChanged(DRAG_OVER_PC, true);
                e.consume();
            });

            setOnDragExited(e -> {
                pseudoClassStateChanged(DRAG_OVER_PC, false);
                e.consume();
            });

            // ── Drag target — drop ────────────────────────────────────────
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
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
            } else if (isEditing()) {
                if (textField != null) textField.setText(displayName(item));
                setText(null);
                setGraphic(textField);
            } else {
                setText(displayName(item));
                setGraphic(null);
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
            setText(null);
            setGraphic(textField);
            textField.selectAll();
            textField.requestFocus();
        }

        @Override
        public void cancelEdit() {
            super.cancelEdit();
            setText(displayName(getItem()));
            setGraphic(null);
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
