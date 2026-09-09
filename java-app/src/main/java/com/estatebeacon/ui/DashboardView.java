package com.estatebeacon.ui;

import com.estatebeacon.email.GmailEmailFetcher;
import com.estatebeacon.model.InboundEmail;
import com.estatebeacon.persistence.Ticket;
import com.estatebeacon.persistence.TicketRepository;
import com.estatebeacon.service.ProcessingOutcome;
import com.estatebeacon.service.TicketProcessingService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.ToolBar;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The Estate Beacon dashboard. Mirrors {@code ui.py:InboxSupervisorWindow},
 * trimmed to the core fetch -> classify -> persist -> display loop; CSV
 * export, the auto-fetch timer, and the raw-email dialog aren't ported yet.
 */
@Component
public class DashboardView {

    private static final Logger log = LoggerFactory.getLogger(DashboardView.class);

    private final GmailEmailFetcher emailFetcher;
    private final TicketProcessingService processingService;
    private final TicketRepository ticketRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "estate-beacon-worker");
        t.setDaemon(true);
        return t;
    });

    // The ObservableList itself is plain data (no Toolkit dependency), but every
    // JavaFX Node below it must NOT be constructed here: this class is a Spring
    // singleton bean, built during context refresh in EstateBeaconApp.init() —
    // off the FX Application Thread, before the JavaFX Toolkit has necessarily
    // finished starting. They're built lazily in buildRoot(), which only ever
    // runs from show(Stage), itself only called from Application.start().
    private final ObservableList<Ticket> tickets = FXCollections.observableArrayList();
    private TableView<Ticket> table;
    private VBox detailContent;
    private Label status;
    private ProgressBar progress;
    private Button fetchButton;

    public DashboardView(
            GmailEmailFetcher emailFetcher,
            TicketProcessingService processingService,
            TicketRepository ticketRepository
    ) {
        this.emailFetcher = emailFetcher;
        this.processingService = processingService;
        this.ticketRepository = ticketRepository;
    }

    public void show(Stage stage) {
        stage.setTitle("Estate Beacon");
        stage.setScene(new Scene(buildRoot(), 1200, 700));
        stage.show();

        tickets.setAll(ticketRepository.getAllTickets());
    }

    private BorderPane buildRoot() {
        table = new TableView<>(tickets);
        detailContent = new VBox(16);
        detailContent.setPadding(new Insets(4, 8, 4, 0));
        status = new Label("Ready — click \"Fetch New Emails\" to start.");
        progress = new ProgressBar(0);
        fetchButton = new Button("Fetch New Emails");

        buildTable();

        ScrollPane detailScroll = new ScrollPane(detailContent);
        detailScroll.setFitToWidth(true);
        VBox detailPane = new VBox(new Label("Extracted Details"), detailScroll);
        detailPane.setSpacing(6);
        detailPane.setPadding(new Insets(0, 0, 0, 8));
        VBox.setVgrow(detailScroll, Priority.ALWAYS);

        SplitPane splitPane = new SplitPane(table, detailPane);
        splitPane.setOrientation(Orientation.HORIZONTAL);
        splitPane.setDividerPositions(0.6);

        progress.setVisible(false);
        progress.setPrefWidth(Double.MAX_VALUE);

        fetchButton.setOnAction(e -> onFetchClicked());
        ToolBar toolBar = new ToolBar(fetchButton);

        VBox top = new VBox(toolBar, progress);

        BorderPane root = new BorderPane();
        root.setPadding(new Insets(12));
        root.setTop(top);
        root.setCenter(splitPane);
        root.setBottom(status);
        BorderPane.setMargin(status, new Insets(6, 0, 0, 0));

        table.getSelectionModel().selectedItemProperty().addListener((obs, old, selected) -> showDetail(selected));

        return root;
    }

    // e.g. "Tue 8 Sep 9:43pm" — the raw cell value stays the ISO-8601 dateReceived
    // string (so the column still sorts chronologically); only the rendered
    // TableCell text is reformatted.
    private static final DateTimeFormatter DISPLAY_DATE_FORMAT =
            DateTimeFormatter.ofPattern("EEE d MMM h:mm", Locale.ENGLISH);

    private static String formatDisplayDate(String isoDateTime) {
        try {
            LocalDateTime dt = LocalDateTime.parse(isoDateTime);
            String ampm = dt.getHour() < 12 ? "am" : "pm";
            return dt.format(DISPLAY_DATE_FORMAT) + ampm;
        } catch (Exception e) {
            return isoDateTime;
        }
    }

    private void buildTable() {
        TableColumn<Ticket, String> dateCol = new TableColumn<>("Received Date");
        dateCol.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().dateReceived()));
        dateCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String isoDateTime, boolean empty) {
                super.updateItem(isoDateTime, empty);
                setText(empty || isoDateTime == null ? null : formatDisplayDate(isoDateTime));
            }
        });
        dateCol.setPrefWidth(150);

        TableColumn<Ticket, String> subjectCol = new TableColumn<>("Subject");
        subjectCol.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().subject()));
        subjectCol.setPrefWidth(280);

        TableColumn<Ticket, String> classificationCol = new TableColumn<>("Classification");
        classificationCol.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().classification()));

        TableColumn<Ticket, String> priorityCol = new TableColumn<>("Priority");
        priorityCol.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().priority()));

        TableColumn<Ticket, String> addressCol = new TableColumn<>("Property Address");
        addressCol.setCellValueFactory(cell -> new ReadOnlyStringWrapper(propertyAddress(cell.getValue())));
        addressCol.setPrefWidth(220);

        table.getColumns().setAll(List.of(dateCol, subjectCol, classificationCol, priorityCol, addressCol));
    }

    private String propertyAddress(Ticket ticket) {
        try {
            JsonNode routing = objectMapper.readTree(ticket.extractedJson()).path("routingData");
            String address = routing.path("propertyAddress").asText("");
            return address.isBlank() ? "—" : address;
        } catch (Exception e) {
            return "—";
        }
    }

    // Explicit overrides for fields whose camelCase name doesn't read naturally
    // once split into words; every other key falls back to that generic split.
    private static final Map<String, String> DISPLAY_LABELS = Map.of(
            "entityName", "Contact Name/Dept",
            "entityPhone", "Contact Phone",
            "entityEmail", "Contact Email"
    );

    private static final Map<String, String> SPECIALIST_SECTION_TITLES = Map.of(
            "cityNotice", "City / Legal Notice",
            "maintenance", "Maintenance Request",
            "dispute", "Tenant Dispute"
    );

    private void showDetail(Ticket ticket) {
        detailContent.getChildren().clear();
        if (ticket == null) {
            return;
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(ticket.extractedJson());
        } catch (Exception e) {
            detailContent.getChildren().add(new Label("(Could not parse extracted details)"));
            return;
        }

        JsonNode routing = root.path("routingData");
        String error = root.path("error").asText("");

        if (routing.isMissingNode() || routing.isNull()) {
            Label message = new Label(error.isBlank()
                    ? "No details available."
                    : "An error occurred while processing this email:\n" + error);
            message.setWrapText(true);
            if (!error.isBlank()) {
                message.setStyle("-fx-text-fill: -color-danger-fg;");
            }
            detailContent.getChildren().add(message);
            return;
        }

        detailContent.getChildren().add(renderSection("Overview", routing));

        for (var entry : SPECIALIST_SECTION_TITLES.entrySet()) {
            JsonNode data = root.path(entry.getKey());
            if (!data.isMissingNode() && !data.isNull()) {
                detailContent.getChildren().add(renderSection(entry.getValue(), data));
            }
        }

        if (!error.isBlank()) {
            Label errorLabel = new Label("Error: " + error);
            errorLabel.setWrapText(true);
            errorLabel.setStyle("-fx-text-fill: -color-danger-fg;");
            detailContent.getChildren().add(errorLabel);
        }
    }

    /** Renders one titled block of humanized key/value rows, e.g. "Overview". */
    private Node renderSection(String title, JsonNode data) {
        Label heading = new Label(title);
        heading.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");

        GridPane grid = new GridPane();
        grid.setHgap(14);
        grid.setVgap(6);
        ColumnConstraints keyColumn = new ColumnConstraints();
        keyColumn.setMinWidth(Region.USE_PREF_SIZE);
        ColumnConstraints valueColumn = new ColumnConstraints();
        valueColumn.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().setAll(keyColumn, valueColumn);

        int row = 0;
        for (Map.Entry<String, JsonNode> field : data.properties()) {
            Label keyLabel = new Label(humanize(field.getKey()));
            keyLabel.setStyle("-fx-text-fill: -color-fg-muted;");

            Label valueLabel = new Label(formatValue(field.getValue()));
            valueLabel.setWrapText(true);
            GridPane.setHgrow(valueLabel, Priority.ALWAYS);

            grid.addRow(row++, keyLabel, valueLabel);
        }

        VBox section = new VBox(6, heading, grid);
        return section;
    }

    private static String formatValue(JsonNode value) {
        if (value.isBoolean()) {
            return value.asBoolean() ? "Yes" : "No";
        }
        String text = value.asText("").strip();
        return text.isEmpty() ? "—" : text;
    }

    /** "propertyAddress" -> "Property Address"; explicit overrides win first. */
    private static String humanize(String camelCaseKey) {
        String override = DISPLAY_LABELS.get(camelCaseKey);
        if (override != null) {
            return override;
        }
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < camelCaseKey.length(); i++) {
            char c = camelCaseKey.charAt(i);
            if (i == 0) {
                result.append(Character.toUpperCase(c));
            } else if (Character.isUpperCase(c)) {
                result.append(' ').append(c);
            } else {
                result.append(c);
            }
        }
        return result.toString();
    }

    private void onFetchClicked() {
        fetchButton.setDisable(true);
        progress.setVisible(true);
        progress.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        status.setText("Connecting to mail server...");

        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                emailFetcher.connect();
                List<InboundEmail> emails = emailFetcher.fetchUnreadEmails();
                emailFetcher.disconnect();

                if (emails.isEmpty()) {
                    updateMessage("No unread emails found.");
                    return null;
                }

                // Gmail's UNREAD label doesn't change just because this app looked at a
                // message (the app only holds the read-only scope, and never marks
                // messages read) — so the same still-unread emails come back on every
                // fetch. Skip ones already in the database instead of re-running the
                // classification pipeline (and re-billing Gemini) on them every time.
                var knownEmailIds = ticketRepository.getProcessedEmailIds();

                updateProgress(0, emails.size());
                int processed = 0;
                for (InboundEmail email : emails) {
                    if (knownEmailIds.contains(email.emailId())) {
                        log.info("Skipping already-processed email: {}", email.subject());
                        processed++;
                        updateProgress(processed, emails.size());
                        updateMessage("Skipped " + processed + " / " + emails.size() + " (already processed)...");
                        continue;
                    }
                    try {
                        ProcessingOutcome outcome = processingService.process(email);
                        if (outcome.ignored()) {
                            log.info("Discarding IGNORED email: {}", email.subject());
                        }
                    } catch (Exception e) {
                        log.error("Processing failed for {}", email.subject(), e);
                        updateMessage("Processing error: " + e.getMessage());
                    }
                    processed++;
                    updateProgress(processed, emails.size());
                    updateMessage("Processed " + processed + " / " + emails.size() + " email(s)...");
                }
                return null;
            }
        };

        status.textProperty().bind(task.messageProperty());
        progress.progressProperty().bind(task.progressProperty());

        task.setOnSucceeded(e -> {
            status.textProperty().unbind();
            progress.progressProperty().unbind();
            tickets.setAll(ticketRepository.getAllTickets());
            status.setText("Done — " + tickets.size() + " ticket(s) in database.");
            progress.setVisible(false);
            fetchButton.setDisable(false);
        });
        task.setOnFailed(e -> {
            status.textProperty().unbind();
            progress.progressProperty().unbind();
            Throwable ex = task.getException();
            status.setText("Fetch error: " + (ex != null ? ex.getMessage() : "unknown error"));
            progress.setVisible(false);
            fetchButton.setDisable(false);
            log.error("Fetch cycle failed", ex);
        });

        executor.submit(task);
    }
}
