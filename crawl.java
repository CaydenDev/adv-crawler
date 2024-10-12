import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AdvancedWebCrawlerGUI extends Application {

    private final int MAX_THREADS = 10;
    private ExecutorService executorService;
    private ConcurrentMap<String, PageInfo> crawledPages;
    private Set<String> visitedUrls;
    private BlockingQueue<CrawlTask> taskQueue;

    private TextField urlField;
    private TextField domainFilterField;
    private Spinner<Integer> depthSpinner;
    private Button startButton;
    private TextArea logArea;
    private ProgressBar progressBar;
    private LineChart<Number, Number> crawlChart;
    private XYChart.Series<Number, Number> dataSeries;

    private volatile boolean isCrawling = false;
    private int totalPagesCrawled = 0;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage primaryStage) {
        primaryStage.setTitle("Advanced Web Crawler");

        BorderPane root = new BorderPane();
        root.setTop(createControlPanel());
        root.setCenter(createVisualizationPanel());
        root.setBottom(createLogPanel());

        Scene scene = new Scene(root, 800, 600);
        primaryStage.setScene(scene);
        primaryStage.show();

        initializeCrawler();
    }

    private VBox createControlPanel() {
        VBox controlPanel = new VBox(10);
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);

        urlField = new TextField();
        urlField.setPromptText("Enter starting URL");
        grid.add(new Label("Start URL:"), 0, 0);
        grid.add(urlField, 1, 0);

        domainFilterField = new TextField();
        domainFilterField.setPromptText("Enter domain filter");
        grid.add(new Label("Domain Filter:"), 0, 1);
        grid.add(domainFilterField, 1, 1);

        depthSpinner = new Spinner<>(1, 10, 3);
        grid.add(new Label("Max Depth:"), 0, 2);
        grid.add(depthSpinner, 1, 2);

        startButton = new Button("Start Crawling");
        startButton.setOnAction(e -> toggleCrawling());
        grid.add(startButton, 1, 3);

        progressBar = new ProgressBar(0);
        progressBar.setMaxWidth(Double.MAX_VALUE);
        grid.add(progressBar, 0, 4, 2, 1);

        controlPanel.getChildren().addAll(grid);
        return controlPanel;
    }

    private VBox createVisualizationPanel() {
        VBox visualizationPanel = new VBox(10);

        NumberAxis xAxis = new NumberAxis();
        NumberAxis yAxis = new NumberAxis();
        crawlChart = new LineChart<>(xAxis, yAxis);
        crawlChart.setTitle("Crawl Progress");
        xAxis.setLabel("Time (seconds)");
        yAxis.setLabel("Pages Crawled");

        dataSeries = new XYChart.Series<>();
        dataSeries.setName("Crawl Rate");
        crawlChart.getData().add(dataSeries);

        visualizationPanel.getChildren().add(crawlChart);
        return visualizationPanel;
    }

    private VBox createLogPanel() {
        VBox logPanel = new VBox(10);
        logArea = new TextArea();
        logArea.setEditable(false);
        logArea.setWrapText(true);
        logPanel.getChildren().add(logArea);
        return logPanel;
    }

    private void initializeCrawler() {
        executorService = Executors.newFixedThreadPool(MAX_THREADS);
        crawledPages = new ConcurrentHashMap<>();
        visitedUrls = Collections.synchronizedSet(new HashSet<>());
        taskQueue = new LinkedBlockingQueue<>();
    }

    private void toggleCrawling() {
        if (!isCrawling) {
            startCrawling();
        } else {
            stopCrawling();
        }
    }

    private void startCrawling() {
        String startUrl = urlField.getText();
        String domainFilter = domainFilterField.getText();
        int maxDepth = depthSpinner.getValue();

        if (startUrl.isEmpty() || domainFilter.isEmpty()) {
            showAlert("Error", "Please enter a starting URL and domain filter.");
            return;
        }

        isCrawling = true;
        startButton.setText("Stop Crawling");
        clearPreviousResults();

        taskQueue.offer(new CrawlTask(startUrl, 0));

        for (int i = 0; i < MAX_THREADS; i++) {
            executorService.submit(new CrawlerWorker(maxDepth, domainFilter));
        }


        new Thread(this::updateChart).start();
    }

    private void stopCrawling() {
        isCrawling = false;
        startButton.setText("Start Crawling");
        executorService.shutdownNow();
        initializeCrawler();
    }

    private void clearPreviousResults() {
        crawledPages.clear();
        visitedUrls.clear();
        taskQueue.clear();
        totalPagesCrawled = 0;
        logArea.clear();
        dataSeries.getData().clear();
        progressBar.setProgress(0);
    }

    private void updateChart() {
        long startTime = System.currentTimeMillis();
        while (isCrawling) {
            try {
                Thread.sleep(1000);
                long elapsedSeconds = (System.currentTimeMillis() - startTime) / 1000;
                Platform.runLater(() -> {
                    dataSeries.getData().add(new XYChart.Data<>(elapsedSeconds, totalPagesCrawled));
                    progressBar.setProgress((double) totalPagesCrawled / 1000);
                });
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private class CrawlerWorker implements Runnable {
        private final int maxDepth;
        private final String domainFilter;

        CrawlerWorker(int maxDepth, String domainFilter) {
            this.maxDepth = maxDepth;
            this.domainFilter = domainFilter;
        }

        @Override
        public void run() {
            while (isCrawling) {
                try {
                    CrawlTask task = taskQueue.poll(1, TimeUnit.SECONDS);
                    if (task != null) {
                        processPage(task.url, task.depth);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        private void processPage(String url, int depth) {
            if (depth > maxDepth || !url.contains(domainFilter) || visitedUrls.contains(url)) {
                return;
            }

            visitedUrls.add(url);
            PageInfo pageInfo = fetchPage(url);

            if (pageInfo != null) {
                crawledPages.put(url, pageInfo);
                totalPagesCrawled++;
                updateLog("Crawled: " + url + " (Depth: " + depth + ")");

                for (String link : pageInfo.links) {
                    taskQueue.offer(new CrawlTask(link, depth + 1));
                }
            }
        }

        private PageInfo fetchPage(String url) {
            try {
                URL pageUrl = new URL(url);
                HttpURLConnection conn = (HttpURLConnection) pageUrl.openConnection();
                conn.setRequestMethod("GET");

                if (conn.getResponseCode() == HttpURLConnection.HTTP_OK) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder content = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        content.append(line);
                    }
                    reader.close();

                    String pageContent = content.toString();
                    List<String> links = extractLinks(pageContent);
                    return new PageInfo(url, pageContent, links, conn.getContentType());
                }
            } catch (IOException e) {
                updateLog("Error fetching " + url + ": " + e.getMessage());
            }
            return null;
        }

        private List<String> extractLinks(String content) {
            List<String> links = new ArrayList<>();
            Pattern pattern = Pattern.compile("href=\"(http[s]?://.*?)\"");
            Matcher matcher = pattern.matcher(content);
            while (matcher.find()) {
                links.add(matcher.group(1));
            }
            return links;
        }
    }

    private static class CrawlTask {
        final String url;
        final int depth;

        CrawlTask(String url, int depth) {
            this.url = url;
            this.depth = depth;
        }
    }

    private static class PageInfo {
        final String url;
        final String content;
        final List<String> links;
        final String contentType;

        PageInfo(String url, String content, List<String> links, String contentType) {
            this.url = url;
            this.content = content;
            this.links = links;
            this.contentType = contentType;
        }
    }

    private void updateLog(String message) {
        Platform.runLater(() -> logArea.appendText(message + "\n"));
    }

    private void showAlert(String title, String content) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(content);
        alert.showAndWait();
    }

    @Override
    public void stop() {
        executorService.shutdownNow();
    }
}
