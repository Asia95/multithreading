package labs.pm.data;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.text.MessageFormat;
import java.text.NumberFormat;
import java.text.ParseException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.FormatStyle;
import java.util.*;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;


/**
 * Create Factory Method Pattern
 */
public class ProductManager {
    private Map<Product, List<Review>> products = new HashMap<>();
//    private ResourceFormatter formatter;
    private final ResourceBundle config = ResourceBundle.getBundle("config");
    private final MessageFormat reviewFormat = new MessageFormat(config.getString("review.data.format"));
    private final MessageFormat productFormat = new MessageFormat(config.getString("product.data.format"));
    private final Path reportsFolder = Path.of(config.getString("reports.folder"));
    private final Path dataFolder = Path.of(config.getString("data.folder"));
    private final Path tempFolder = Path.of(config.getString("temp.folder"));
    private static final Map<String, ResourceFormatter> formatters
            = Map.of("en-GB", new ResourceFormatter(Locale.UK),
            "en-US", new ResourceFormatter(Locale.US),
            "es-ES", new ResourceFormatter(new Locale("es", "ES")),
            "fr-FR", new ResourceFormatter(Locale.FRANCE),
            "ru-RU", new ResourceFormatter(new Locale("ru", "RU")),
            "zh-Ch", new ResourceFormatter(Locale.CHINA));

    private static final ProductManager pm = new ProductManager();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final Lock writeLock = lock.writeLock();
    private final Lock readLock = lock.readLock();

    private static final Logger logger = Logger.getLogger(ProductManager.class.getName());


//    public ProductManager(Locale locale) {
//        this(locale.toLanguageTag());
//    }

    private ProductManager() { loadAllData(); } // to make sure ProductManager is a singleton, it can't be run from other classes
    // singleton is a design pattern that guarantees a creation of exactly one instance of a given class

//    public void changeLocale(String languageTag) {
//        formatter = formatters.getOrDefault(languageTag, formatters.get("en-GB"));
//    }

    public static ProductManager getInstance() { // returns always the same instance of ProductManager
        return pm;
    }

    public static Set<String> getSupportedLocales() {
        return formatters.keySet();
    }


    public Product CreateProduct(int id, String name, BigDecimal price, Rating rating, LocalDate bestBefore) {
        Product product = null;
        try {
            writeLock.lock();
            product = new Food(id, name, price, rating, bestBefore);
            products.putIfAbsent(product, new ArrayList<>());
        } catch (Exception ex) {
            logger.log(Level.INFO, "Error adding product " + ex.getMessage());
        } finally {
            writeLock.unlock();
        }
        return product;
    }

    public Product CreateProduct(int id, String name, BigDecimal price, Rating rating) {
        Product product = null;
        try {
            writeLock.lock();
            product = new Drink(id, name, price, rating);
            products.putIfAbsent(product, new ArrayList<>());
        } catch (Exception ex) {
            logger.log(Level.INFO, "Error adding product " + ex.getMessage());
        } finally {
            writeLock.unlock();
        }
        return product;
    }

    public Product reviewProduct(int productId, Rating rating, String comments) {
        try {
            writeLock.lock();
            return reviewProduct(findProduct(productId), rating, comments);
        } catch (ProductManagementException e){
           logger.log(Level.INFO,e.getMessage());
        } finally {
            writeLock.unlock();
        }
        return null;
    }

    //we are just assuming here that the private method won't be called anywhere else, hence we are not adding locks to it
    //but this is not a good design, as this cannot be guaranteed
    private Product reviewProduct(Product product, Rating rating, String comments) {
        List<Review> reviews = products.get(product);
        products.remove(product, reviews);
        reviews.add(new Review(rating, comments));

        product = product.applyRating(
                Rateable.convert(
                        (int) Math.round(
                                reviews.stream()
                                        .mapToInt(p -> p.getRating().ordinal())
                                        .average()
                                        .orElse(0))));

        products.put(product, reviews);
        return product;
    }

    public Product findProduct(int id) throws ProductManagementException {
        try {
            readLock.lock();
            return products.keySet()
                    .stream()
                    .filter(p -> p.getId() == id)
                    .findFirst()
                    .orElseThrow(()-> new ProductManagementException("Product with id " + id + "not found"));
        } finally {
            readLock.unlock();
        }
    }

    public void printProductReport(int productId, String languageTag, String client) {
        try {
            readLock.lock();
            printProductReport(findProduct(productId), languageTag, client);
        } catch (ProductManagementException | IOException e){
            logger.log(Level.INFO, e.getMessage());
        } finally {
            readLock.unlock();
        }
    }

    private void printProductReport(Product product, String languageTag, String client) throws UnsupportedEncodingException, IOException {
        ResourceFormatter formatter = formatters.getOrDefault(languageTag, formatters.get("en-US"));
        List<Review> reviews = products.get(product);
        Collections.sort(reviews);
        Path productFile = reportsFolder.resolve(MessageFormat.format(config.getString("report.file"), product.getId(), client));
        try (PrintWriter out = new PrintWriter(new OutputStreamWriter(Files.newOutputStream(productFile, StandardOpenOption.CREATE), "UTF-8"))) {
            out.append(formatter.formatProduct(product) + System.lineSeparator());
            if(reviews.isEmpty()){
                out.append(formatter.getText("no.reviews") + System.lineSeparator());
            }
            else {
                out.append(reviews.stream().map(r -> formatter.formatReview(r) + "\n").collect(Collectors.joining()));
            }
        }
    }

    public void printProducts(Predicate<Product> filter, Comparator<Product> sorter, String languageTag) {
        try {
            readLock.lock();
            ResourceFormatter formatter = formatters.getOrDefault(languageTag, formatters.get("en-GB"));
            StringBuilder txt = new StringBuilder();

            txt.append(products
                    .keySet()
                    .stream()
                    .sorted(sorter)
                    .filter(filter)
                    .map(p -> formatter.formatProduct(p) + '\n')
                    .collect(Collectors.joining()));

            System.out.println(txt);
        } finally {
            readLock.unlock();
        }
    }

    private Product parseProduct(String text) {
        Product product = null;
        try {
            Object[] values = productFormat.parse(text);
            int id = Integer.parseInt((String)values[1]);
            String name = (String)values[2];
            BigDecimal price = BigDecimal.valueOf(Double.parseDouble((String)values[3]));
            Rating rating = Rateable.convert(Integer.parseInt((String)values[4]));
            switch ( (String)values[0]) {
                case "D":
                    product = new Drink(id, name, price, rating);
                    break;

                case "F":
                    LocalDate bestBefore = LocalDate.parse((String)values[5]);
                    product = new Food(id, name, price, rating, bestBefore);
            }
        } catch (ParseException | NumberFormatException | DateTimeParseException e ) {
            logger.log(Level.WARNING, "Error parsing product" + text, e );
        }
        return product;
    }

    private Review parseReview(String text) {
        Review review = null;
        try {
            Object[] values = reviewFormat.parse(text);
            review = new Review(Rateable.convert(Integer.parseInt((String)values[1])), (String)values[2]);
        } catch (ParseException | NumberFormatException e ) {
            logger.log(Level.WARNING, "Error parsing review" + text, e );
        }
        return review;
    }

    private void loadAllData() {
        try {
            products = Files.list(dataFolder)
                    .filter(file -> file.getFileName().toString().startsWith("product"))
                    .map(file -> loadProduct(file))
                    .filter(product -> product != null)
                    .collect(Collectors.toMap(product -> product, product -> loadReviews(product)));
        }
        catch (IOException e) {
            logger.log(Level.SEVERE, "Error loading data" + e.getMessage(), e);
        }
    }

    private Product loadProduct(Path file) {
        Product product = null;
        try {
            product = parseProduct(Files.lines( dataFolder.resolve(file), Charset.forName("UTF-8"))
                    .findFirst().orElseThrow());
        } catch (Exception e) {
            logger.log(Level.WARNING, "Error loading products " + e.getMessage());
        }
        return product;
    }

    private List<Review> loadReviews(Product product){
        List<Review> reviews = null;
        Path file = dataFolder.resolve(MessageFormat.format(config.getString("reviews.data.file"), product.getId()));
        if(Files.notExists(file)) {
            reviews = new ArrayList<Review>();
        }
        else {
            try {
                reviews = Files.lines(file, Charset.forName("UTF-8"))
                        .map(text -> parseReview(text))
                        .filter(review -> review != null)
                        .collect(Collectors.toList());

            }
            catch (IOException e) {
                logger.log(Level.WARNING, "Error loading reviews " + e.getMessage());
            }
        }
        return reviews;
    }

    public Map<String,String> getDiscount(String languageTag) {
        try {
            readLock.lock();
            ResourceFormatter formatter = formatters.getOrDefault(languageTag, formatters.get("en-GB"));
            return products.keySet()
                    .stream()
                    .collect(Collectors.groupingBy(
                            product -> product.getRating().getStars(),
                            Collectors.collectingAndThen(
                                    Collectors.summingDouble(
                                            product -> product.getDiscount().doubleValue()),
                                    discount -> formatter.moneyFormat.format(discount))));
        } finally {
            readLock.unlock();
        }
    }


    private static class ResourceFormatter {

        private Locale locale;
        private ResourceBundle resources;
        private DateTimeFormatter dateFormat;
        private NumberFormat moneyFormat;

        private ResourceFormatter(Locale locale) {
            this.locale = locale;
            resources = ResourceBundle.getBundle("resources", locale);
            dateFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).localizedBy(locale);
            moneyFormat = NumberFormat.getCurrencyInstance(locale);
        }

        private String formatProduct(Product product) {
            return MessageFormat.format(resources.getString("product"),
                    product.getName(),
                    moneyFormat.format(product.getPrice()),
                    product.getRating().getStars(),
                    dateFormat.format(product.getBestBefore()));
        }

        private String formatReview(Review review) {
            return MessageFormat.format(
                    resources.getString("review"),
                    review.getRating().getStars(),
                    review.getComments());
        }

        private String getText(String key) {
            return resources.getString(key);
        }
    }
}
