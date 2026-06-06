package at.fedo.notes.controller;

import javafx.fxml.FXML;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class MainController {

    @FXML private ListView<String> noteList;
    @FXML private TextArea editor;
    @FXML private TextField titleField;

    private static final Path NOTES_DIR = Paths.get(System.getProperty("user.home"), "Documents", "Notes");

    @FXML
    public void initialize() throws IOException {
        Files.createDirectories(NOTES_DIR);
        loadNoteList();

        noteList.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null) openNote(newVal);
        });
    }

    private void loadNoteList() throws IOException {
        noteList.getItems().clear();
        Files.list(NOTES_DIR)
             .filter(p -> p.toString().endsWith(".txt"))
             .map(p -> p.getFileName().toString().replace(".txt", ""))
             .forEach(noteList.getItems()::add);
    }

    @FXML
    private void newNote() throws IOException {
        String name = "Note " + (noteList.getItems().size() + 1);
        Files.writeString(NOTES_DIR.resolve(name + ".txt"), "");
        loadNoteList();
        noteList.getSelectionModel().select(name);
    }

    @FXML
    private void saveNote() throws IOException {
        String selected = noteList.getSelectionModel().getSelectedItem();
        if (selected == null) return;
        Files.writeString(NOTES_DIR.resolve(selected + ".txt"), editor.getText());
    }

    @FXML
    private void deleteNote() throws IOException {
        String selected = noteList.getSelectionModel().getSelectedItem();
        if (selected == null) return;
        Files.deleteIfExists(NOTES_DIR.resolve(selected + ".txt"));
        editor.clear();
        loadNoteList();
    }

    private void openNote(String name) {
        try {
            editor.setText(Files.readString(NOTES_DIR.resolve(name + ".txt")));
        } catch (IOException e) {
            editor.setText("");
        }
    }
}
