package us.n8l.links;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;

public class GetShortLinkServlet extends HttpServlet {
    Logger log = LoggerFactory.getLogger(getClass());

    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String pathInfo = request.getPathInfo();
        String shortUrl = StringUtils.isNotEmpty(pathInfo) ? pathInfo.substring(1) : "";
        if (StringUtils.isBlank(shortUrl)) {
            log.warn("Blank short url.");
            response.sendError(404, "You must provide a non-blank short URL.");
            return;
        }
        log.info("Got short URL request for : " + shortUrl);
        Optional<String> fullUrl = DigestURLRepository.get().getUrlForShortUrl(shortUrl);
        if (fullUrl.isPresent()) {
            log.info("Redirecting '" + shortUrl + "' to : " + fullUrl.get());
            response.sendRedirect(fullUrl.get());
        } else {
            log.info("No full URL found for : " + shortUrl);
            response.sendError(404, "No such short url code saved:" + shortUrl);
        }
    }
}