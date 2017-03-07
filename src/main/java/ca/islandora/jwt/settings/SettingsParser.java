package ca.islandora.jwt.settings;

import com.auth0.jwt.algorithms.Algorithm;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.digester.Digester;
import org.bouncycastle.util.io.pem.PemObject;
import org.bouncycastle.util.io.pem.PemReader;
import org.xml.sax.SAXException;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public class SettingsParser {
    private static Digester digester = null;
    private static Log log = LogFactory.getLog(JwtSite.class);
    private enum AlgorithmType {INVALID, RSA, HMAC};

    protected static Digester getDigester() {
        if (digester == null) {
            digester = new Digester();
            digester.setValidating(false);
            digester.addObjectCreate("sites", "ca.islandora.jwt.settings.JwtSites");
            digester.addSetProperties("sites");
            digester.addObjectCreate("sites/site", "ca.islandora.jwt.settings.JwtSite");
            digester.addSetProperties("sites/site");
            digester.addCallMethod("sites/site", "setKey", 0);
            digester.addSetNext("sites/site", "addSite", "ca.islandora.jwt.settings.JwtSite");
        }
        return digester;
    }


    private static AlgorithmType getSiteAlgorithmType(String algorithm) {
        if (algorithm.equalsIgnoreCase("RS256")) {
            return AlgorithmType.RSA;
        }
        else if (algorithm.equalsIgnoreCase("RS384")) {
            return AlgorithmType.RSA;
        }
        else if (algorithm.equalsIgnoreCase("RS512")) {
            return AlgorithmType.RSA;
        }
        if (algorithm.equalsIgnoreCase("HS256")) {
            return AlgorithmType.HMAC;
        }
        else if (algorithm.equalsIgnoreCase("HS384")) {
            return AlgorithmType.HMAC;
        }
        else if (algorithm.equalsIgnoreCase("HS512")) {
            return AlgorithmType.HMAC;
        }
        else {
            return AlgorithmType.INVALID;
        }
    }

    private static boolean validateExpandPath(JwtSite site) {
        File file = new File(site.getPath());
        if (!file.isAbsolute())
            file = new File(System.getProperty("catalina.base"), site.getPath());
        if (!file.exists() || !file.canRead()) {
            log.error("Path does not exist:" + site.getPath() + ". Site ignored.");
            return false;
        }
        site.setPath(file.getAbsolutePath());
        return true;
    }

    protected static Algorithm getRsaAlgorithm(JwtSite site) {
        Reader publicKeyReader = null;
        RSAPublicKey publicKey = null;

        if (!site.getKey().equalsIgnoreCase("")) {
            publicKeyReader = new StringReader(site.getKey());
        }
        else if (site.getPath() != null) {
            try {
                publicKeyReader = new FileReader(site.getPath());
            }
            catch (FileNotFoundException e) {
                log.error("Private key file not found.");
                return null;
            }
        }

        if (site.getEncoding().equalsIgnoreCase("pem")) {
            try {
                PemReader pemReader = new PemReader(publicKeyReader);
                KeyFactory factory = KeyFactory.getInstance("RSA");
                PemObject pemObject = pemReader.readPemObject();
                X509EncodedKeySpec pubKeySpec = new X509EncodedKeySpec(pemObject.getContent());
                publicKey = (RSAPublicKey) factory.generatePublic(pubKeySpec);
                pemReader.close();
                publicKeyReader.close();
            }
            catch (Exception e) {
                log.error("Error loading public key.");
                return null;
            }
        }

        if (publicKey == null) {
            return null;
        }

        if (site.getAlgorithm().equalsIgnoreCase("RS256")) {
            return Algorithm.RSA256(publicKey);
        }
        else if (site.getAlgorithm().equalsIgnoreCase("RS384")) {
            return Algorithm.RSA384(publicKey);
        }
        else if (site.getAlgorithm().equalsIgnoreCase("RS512")) {
            return Algorithm.RSA512(publicKey);
        }
        else {
            return null;
        }
    }

    protected static Algorithm getHmacAlgorithm(JwtSite site) {
        byte[] secret;
        byte[] secretRaw = null;

        if (!site.getKey().equalsIgnoreCase("")) {
            secretRaw = site.getKey().trim().getBytes();
        }
        else if (site.getPath() != null) {
            try {
                secretRaw = Files.readAllBytes(Paths.get(site.getPath()));
            }
            catch (IOException e) {
                log.error("Unable to get secret from file.", e);
                return null;
            }
        }


        if (site.getEncoding().equalsIgnoreCase("base64")) {
            try {
                secret = Base64.getDecoder().decode(secretRaw);
            }
            catch (Exception e) {
                log.error("Base64 decode error. Skipping site.", e);
                return null;
            }
        }
        else if (site.getEncoding().equalsIgnoreCase("plain")) {
            secret = secretRaw;
        }
        else {
            return null;
        }

        if (site.getAlgorithm().equalsIgnoreCase("HS256")) {
            return Algorithm.HMAC256(secret);
        }
        else if (site.getAlgorithm().equalsIgnoreCase("HS384")) {
            return Algorithm.HMAC384(secret);
        }
        else if (site.getAlgorithm().equalsIgnoreCase("HS512")) {
            return Algorithm.HMAC512(secret);
        }
        else {
            return null;
        }
    }

    public static Map<String, Algorithm> getSiteAlgorithms(InputStream settings) {
        Map<String, Algorithm> algorithms = new HashMap<>();
        JwtSites sites = null;

        try {
            sites = getSitesObject(settings);
        }
        catch(Exception e) {
            log.error("Error loading settings file.", e);
            return algorithms;
        }

        if (sites.getVersion() != 1) {
            log.error("Incorrect XML version. Aborting.");
            return algorithms;
        }

        JwtSite site;
        boolean defaultSet = false;

        for(Iterator<JwtSite> sitesIterator = sites.getSites().iterator(); sitesIterator.hasNext();) {
            site = sitesIterator.next();

            boolean pathDefined = site.getPath() != null && !site.getPath().equalsIgnoreCase("");
            boolean keyDefined = site.getKey() != null && !site.getKey().equalsIgnoreCase("");

            // Check that we don't have both a key and a path defined
            if (!(pathDefined ^ keyDefined)) {
                log.error("Only one of path or key must be defined.");
                continue;
            }

            if (site.getPath() != null) {
                if(!validateExpandPath(site)) {
                    continue;
                }
            }

            // Check that the algorithm type is valid.
            AlgorithmType algorithmType = getSiteAlgorithmType(site.getAlgorithm());
            Algorithm algorithm = null;
            if (algorithmType == AlgorithmType.HMAC) {
                algorithm = getHmacAlgorithm(site);
            }
            else if (algorithmType == AlgorithmType.RSA) {
                algorithm = getRsaAlgorithm(site);
            }
            else {
                log.error("Invalid algorithm selection: " + site.getAlgorithm() + ". Site ignored." );
                continue;
            }

            if ((site.getUrl() == null || site.getUrl().equalsIgnoreCase("")) && !site.getDefault()) {
                log.error("Site URL must be defined for non-default sites.");
                continue;
            }

            if(site.getDefault()) {
                if (defaultSet == true) {
                    log.error("Multiple default sites specified in configuration.");
                    continue;
                }
                defaultSet = true;
            }

            if(algorithm != null) {
                String name = site.getDefault() ? null : site.getUrl();
                algorithms.put(name, algorithm);
            }
        }

        return algorithms;
    }

    protected static JwtSites getSitesObject(InputStream settings)
            throws IOException, SAXException
    {
        JwtSites sitesConfig = (JwtSites) getDigester().parse(settings);
        return sitesConfig;
    }
}
