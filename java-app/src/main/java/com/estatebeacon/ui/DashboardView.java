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
import javafx.geometry.Rectangle2D;
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
import javafx.stage.Screen;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
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
    private static final double DEFAULT_WIDTH = 1200;
    private static final double DEFAULT_HEIGHT = 700;

    private final GmailEmailFetcher emailFetcher;
    private final TicketProcessingService processingService;
    private final TicketRepository ticketRepository;
    private final UiStateStore uiStateStore;
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

    // Tracks the window's last non-maximized bounds, since Stage.getWidth()/getHeight() report
    // the full screen size while maximized — restoring into those would leave no way back to a
    // normal-sized window.
    private double trackedX;
    private double trackedY;
    private double trackedWidth = DEFAULT_WIDTH;
    private double trackedHeight = DEFAULT_HEIGHT;

    public DashboardView(
            GmailEmailFetcher emailFetcher,
            TicketProcessingService processingService,
            TicketRepository ticketRepository,
            UiStateStore uiStateStore
    ) {
        this.emailFetcher = emailFetcher;
        this.processingService = processingService;
        this.ticketRepository = ticketRepository;
        this.uiStateStore = uiStateStore;
    }

    public void show(Stage stage) {
        stage.setTitle("Estate Beacon");
        BorderPane root = buildRoot();

        UiState savedState = uiStateStore.load().orElse(null);
        double width = savedState != null ? savedState.windowWidth() : DEFAULT_WIDTH;
        double height = savedState != null ? savedState.windowHeight() : DEFAULT_HEIGHT;
        stage.setScene(new Scene(root, width, height));

        if (savedState != null) {
            applyColumnWidths(savedState.columnWidths());
            if (isOnScreen(savedState.windowX(), savedState.windowY(), width, height)) {
                stage.setX(savedState.windowX());
                stage.setY(savedState.windowY());
            }
        }
        trackedWidth = width;
        trackedHeight = height;

        trackWindowBounds(stage);
        stage.setOnCloseRequest(e -> saveUiState(stage));

        stage.show();
        if (savedState != null && savedState.maximized()) {
            stage.setMaximized(true);
        }

        loadTicketsAsync();
    }

    /** A saved position is only useful if it still lands on a currently connected screen (e.g. an external monitor). */
    private static boolean isOnScreen(double x, double y, double width, double height) {
        Rectangle2D bounds = new Rectangle2D(x, y, width, height);
        return Screen.getScreens().stream().anyMatch(screen -> screen.getVisualBounds().intersects(bounds));
    }

    /** Keeps {@link #trackedX}/{@link #trackedY}/{@link #trackedWidth}/{@link #trackedHeight} in sync while not maximized. */
    private void trackWindowBounds(Stage stage) {
        stage.xProperty().addListener((obs, old, value) -> {
            if (!stage.isMaximized()) {
                trackedX = value.doubleValue();
            }
        });
        stage.yProperty().addListener((obs, old, value) -> {
            if (!stage.isMaximized()) {
                trackedY = value.doubleValue();
            }
        });
        stage.widthProperty().addListener((obs, old, value) -> {
            if (!stage.isMaximized()) {
                trackedWidth = value.doubleValue();
            }
        });
        stage.heightProperty().addListener((obs, old, value) -> {
            if (!stage.isMaximized()) {
                trackedHeight = value.doubleValue();
            }
        });
    }

    private void applyColumnWidths(Map<String, Double> columnWidths) {
        if (columnWidths == null) {
            return;
        }
        for (TableColumn<Ticket, ?> column : table.getColumns()) {
            Double width = columnWidths.get(column.getId());
            if (width != null) {
                column.setPrefWidth(width);
            }
        }
    }

    private void saveUiState(Stage stage) {
        Map<String, Double> columnWidths = new LinkedHashMap<>();
        for (TableColumn<Ticket, ?> column : table.getColumns()) {
            if (column.getId() != null) {
                columnWidths.put(column.getId(), column.getWidth());
            }
        }
        uiStateStore.save(new UiState(trackedX, trackedY, trackedWidth, trackedHeight, stage.isMaximized(), columnWidths));
    }

    /** Loads the ticket table off the FX Application Thread, so a large/slow DB doesn't freeze the just-shown window. */
    private void loadTicketsAsync() {
        Task<List<Ticket>> task = new Task<>() {
            @Override
            protected List<Ticket> call() {
                return ticketRepository.getAllTickets();
            }
        };
        task.setOnSucceeded(e -> tickets.setAll(task.getValue()));
        task.setOnFailed(e -> log.error("Failed to load tickets", task.getException()));
        executor.submit(task);
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
        dateCol.setId("date");
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
        subjectCol.setId("subject");
        subjectCol.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().subject()));
        subjectCol.setPrefWidth(280);

        TableColumn<Ticket, String> classificationCol = new TableColumn<>("Classification");
        classificationCol.setId("classification");
        classificationCol.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().classification()));

        TableColumn<Ticket, String> priorityCol = new TableColumn<>("Priority");
        priorityCol.setId("priority");
        priorityCol.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().priority()));

        TableColumn<Ticket, String> addressCol = new TableColumn<>("Property Address");
        addressCol.setId("address");
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

    private record FetchSummary(int totalChecked, int newTickets, int ignored, int failed, int skipped) {
    }

    private void onFetchClicked() {
        fetchButton.setDisable(true);
        progress.setVisible(true);
        progress.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        status.setText("Connecting to mail server...");

        Task<FetchSummary> task = new Task<>() {
            @Override
            protected FetchSummary call() throws Exception {
                emailFetcher.connect(this::updateMessage);
                List<InboundEmail> emails = emailFetcher.fetchUnreadEmails(this::updateMessage);
                emailFetcher.disconnect();

                if (emails.isEmpty()) {
                    updateMessage("No unread emails found.");
                    return new FetchSummary(0, 0, 0, 0, 0);
                }

                // Gmail's UNREAD label doesn't change just because this app looked at a
                // message (the app only holds the read-only scope, and never marks
                // messages read) — so the same still-unread emails come back on every
                // fetch. Skip ones already in the database instead of re-running the
                // classification pipeline (and re-billing Gemini) on them every time.
                var knownEmailIds = ticketRepository.getProcessedEmailIds();

                updateProgress(0, emails.size());
                int done = 0;
                int skipped = 0;
                int newTickets = 0;
                int ignored = 0;
                int failed = 0;
                for (InboundEmail email : emails) {
                    done++;
                    if (knownEmailIds.contains(email.emailId())) {
                        skipped++;
                        updateProgress(done, emails.size());
                        updateMessage("Checked " + done + " / " + emails.size()
                                + " (" + skipped + " already processed, skipped)...");
                        continue;
                    }
                    updateMessage("Processing " + done + " / " + emails.size() + ": "
                            + (email.subject().isBlank() ? "(no subject)" : email.subject()) + "...");
                    try {
                        ProcessingOutcome outcome = processingService.process(email);
                        if (outcome.failed()) {
                            failed++;
                            log.warn("Classification failed for {}: {}", email.subject(), outcome.errorMessage());
                        } else if (outcome.ignored()) {
                            ignored++;
                            log.info("Discarding IGNORED email: {}", email.subject());
                        } else {
                            newTickets++;
                        }
                    } catch (Exception e) {
                        failed++;
                        log.error("Processing failed for {}", email.subject(), e);
                    }
                    updateProgress(done, emails.size());
                    updateMessage("Checked " + done + " / " + emails.size()
                            + " (" + newTickets + " ticketed, " + ignored + " ignored, "
                            + failed + " failed)...");
                }
                return new FetchSummary(emails.size(), newTickets, ignored, failed, skipped);
            }
        };

        status.textProperty().bind(task.messageProperty());
        progress.progressProperty().bind(task.progressProperty());

        task.setOnSucceeded(e -> {
            status.textProperty().unbind();
            progress.progressProperty().unbind();
            tickets.setAll(ticketRepository.getAllTickets());
            FetchSummary summary = task.getValue();
            StringBuilder message = new StringBuilder(String.format(
                    "Done — checked %d, %d new ticket(s), %d ignored",
                    summary.totalChecked(), summary.newTickets(), summary.ignored()));
            if (summary.failed() > 0) {
                message.append(String.format(", %d failed (see log)", summary.failed()));
            }
            message.append(String.format(", %d already known and skipped. %d ticket(s) in database.",
                    summary.skipped(), tickets.size()));
            status.setText(message.toString());
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
