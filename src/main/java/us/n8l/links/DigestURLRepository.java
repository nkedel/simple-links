package us.n8l.links;

import com.google.common.base.Charsets;
import org.h2.jdbcx.JdbcConnectionPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.xml.bind.DatatypeConverter;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Base64;
import java.util.Optional;

public class DigestURLRepository {
    //language=SQL
    public static final String JOIN_SHORT_URL_TO_URL = "SELECT full_urls.URL FROM full_urls JOIN short_urls WHERE full_urls.ID = short_urls.URL_ID AND short_urls.SHORT_URL = ?";
    //language=SQL
    public static final String SELECT_BY_HASH = "SELECT ID,URL FROM full_urls WHERE MD5 = ?";
    //language=SQL
    public static final String SELECT_SHORT_URL = "SELECT URL_ID FROM short_urls WHERE SHORT_URL = ?";
    //language=SQL
    public static final String INSERT_FULL_URL = "INSERT INTO full_urls (MD5, URL) VALUES (?, ?)";
    //language=SQL
    public static final String INSERT_SHORT_URL = "INSERT INTO short_urls (SHORT_URL, URL_ID) VALUES (?, ?)";
    private static Base64.Encoder base64Encoder = Base64.getUrlEncoder();
    private static DigestURLRepository instance = null;
    private Logger log = LoggerFactory.getLogger(getClass());
    final private JdbcConnectionPool pool;
    final private MessageDigest md5;

    private DigestURLRepository() throws NoSuchAlgorithmException, SQLException {
        pool = JdbcConnectionPool.create("jdbc:h2:~/h2/test;INIT=RUNSCRIPT FROM 'classpath:scripts/create.sql'", "", "");
        md5 = MessageDigest.getInstance("MD5");
    }

    synchronized static DigestURLRepository get() {
        if (instance == null) {
            try {
                instance = new DigestURLRepository();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        return instance;
    }

    String getNewShortUrl(@Nonnull final String fullUrl, boolean allowDuplicates) {
        try (Connection connection = pool.getConnection()) {
            byte[] bytesToDigest = fullUrl.getBytes(Charsets.UTF_8);
            byte[] digestBytes = md5.digest(bytesToDigest);
            log.debug("digest "+fullUrl+" => "+ DatatypeConverter.printHexBinary(digestBytes));
            PreparedStatement getByHash = connection.prepareStatement(SELECT_BY_HASH);
            getByHash.setBytes(1, digestBytes);
            ResultSet resultSet = getByHash.executeQuery();
            if (!resultSet.next()) {
                log.debug("ID not found for hash");
                PreparedStatement putNewFullUrl = connection.prepareStatement(INSERT_FULL_URL);
                putNewFullUrl.setBytes(1, digestBytes);
                putNewFullUrl.setString(2, fullUrl);
                putNewFullUrl.executeUpdate();
                getByHash.clearParameters();
                getByHash.setBytes(1, digestBytes);
                resultSet = getByHash.executeQuery();
                resultSet.next();
            }
            long id = resultSet.getLong("ID");
            log.debug("ID not full URL : "+id);
            String urlById = resultSet.getString("URL");
            assert fullUrl.equals(urlById); // right now, we don't handle collisions on a full hash
            while (true) {
                String encoded = base64Encoder.encodeToString(digestBytes);
                log.debug("Hash as base64: "+encoded);
                String candidateShortUrl = encoded.substring(0, 6);
                PreparedStatement checkShortUrl = connection.prepareStatement(SELECT_SHORT_URL);
                checkShortUrl.setString(1, candidateShortUrl);
                ResultSet check = checkShortUrl.executeQuery();
                if (!check.next()) {
                    log.debug("Short URL not found, inserting and returning : "+candidateShortUrl);
                    PreparedStatement insertShortUrl = connection.prepareStatement(INSERT_SHORT_URL);
                    insertShortUrl.setString(1, candidateShortUrl);
                    insertShortUrl.setLong(2, id);
                    int rows = insertShortUrl.executeUpdate();
                    assert rows == 1; // should never see this as we should get SQLException on failure
                    return candidateShortUrl;
                } else if (allowDuplicates && id == check.getLong(1)) {
                    log.debug("Short URL found, duplicates allowed returning : "+candidateShortUrl);
                    return candidateShortUrl;
                }
                // on collision, re-digest our digest.
                log.debug("Short URL found, duplicates not-allowed, re-hashing");
                digestBytes = md5.digest(digestBytes);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }

    }

    Optional<String> getUrlForShortUrl(String shortUrl) {
        try (Connection connection = pool.getConnection()) {
            PreparedStatement select = connection.prepareStatement(JOIN_SHORT_URL_TO_URL);
            select.setString(1, shortUrl);
            ResultSet resultSet = select.executeQuery();
            if (resultSet.first()) {
                String fullUrl = resultSet.getString(1);
                return Optional.of(fullUrl);
            } else {
                return Optional.empty();
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
