package ch.so.agi.sodata.stac;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import ch.so.agi.meta2file.model.BoundingBox;
import ch.so.agi.meta2file.model.Item;
import ch.so.agi.meta2file.model.ThemePublication;
import jakarta.annotation.PostConstruct;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKTReader;
import org.locationtech.jts.io.geojson.GeoJsonWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class ConfigService {
    private final Logger log = LoggerFactory.getLogger(this.getClass());

    private WKTReader wktReader = new WKTReader();
    private GeoJsonWriter geoJsonWriter = new GeoJsonWriter();

    private static final String PYTHON = "python";

    private static final String SOURCE_FILE_NAME = "staccreator.py";

    @org.springframework.beans.factory.annotation.Value("${app.configFile}")
    private String configFile;   

    @org.springframework.beans.factory.annotation.Value("${app.rootHref}")
    private String rootHref; 
    
    @org.springframework.beans.factory.annotation.Value("${app.filesServerUrl}")
    private String filesServerUrl;   
    
    @org.springframework.beans.factory.annotation.Value("${app.stacDirectory}")
    private String stacDirectory;

    @org.springframework.beans.factory.annotation.Value("${app.venvParentPath}")
    private String venvParentPath;

//    @Autowired
//    private Context context;

    private String venvExePath;
    
    private StacCreator stacCreator;

    @PostConstruct
    public void init() throws IOException {
        /* 
         * Im Dev-Modus muss venvParentPath null sein. Dann wird angenommen,
         * dass der venv-Ordner lokal vorhanden ist und nichts gemacht
         * werden muss.
         * Wenn venvParentPath ungleich null ist, bedeutet das, dass wir
         * die venv.zip-Datei aus den Resourcen entpacken müssen.
         */
        if (venvParentPath != null) {
            var VENV_ZIP_NAME = "venv.zip";
            
            try (InputStream is = getClass().getResourceAsStream("/"+VENV_ZIP_NAME)) {
                File file = Paths.get(venvParentPath, new File(VENV_ZIP_NAME).getName()).toFile();
                Files.copy(is, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
                
                String zipFilePath = Paths.get(venvParentPath, VENV_ZIP_NAME).toFile().getAbsolutePath();
                log.debug("<zipFilePath> {}", zipFilePath);
                Zip.unzip(zipFilePath, new File(venvParentPath));
                
                venvExePath = Paths.get(venvParentPath, "venv", "bin", "graalpy").toString();
            }
        } else {
            venvExePath = ConfigService.class.getClassLoader()
                    .getResource(Paths.get("venv", "bin", "graalpy").toString())
                    .getPath();
        }
        
        log.debug("<venvExePath> {}", venvExePath);
    }
    
    public void readXml() throws XMLStreamException, IOException, ParseException {
        var code = new InputStreamReader(ConfigService.class.getClassLoader().getResourceAsStream(SOURCE_FILE_NAME));
        
        // Wird mühsam, wenn er als Bean definiert wird, da er dann den Spring-Boot-Standard-Mapper überschreibt.
        // In unserem Fall brauchen wir den XmlMapper nur gerade hier.
        var xmlMapper = new XmlMapper();
        xmlMapper.registerModule(new JavaTimeModule());
        xmlMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        var xif = XMLInputFactory.newInstance();
        var xr = xif.createXMLStreamReader(new FileInputStream(new File(configFile)));
        
        try (var context = Context.newBuilder("python")
                .allowAllAccess(true)
                .option("python.Executable", venvExePath)
                .option("python.ForceImportSite", "true")
                .build()) {

            context.eval(Source.newBuilder(PYTHON, code, SOURCE_FILE_NAME).build());
            
            Value pystacCreatorClass = context.getPolyglotBindings().getMember("StacCreator");
            Value pystacCreator = pystacCreatorClass.newInstance();

            stacCreator = pystacCreator.as(StacCreator.class);
            
            var collections = new ArrayList<String>();
            while (xr.hasNext()) {
                xr.next();
                if (xr.getEventType() == XMLStreamConstants.START_ELEMENT) {
                    if ("themePublication".equals(xr.getLocalName())) {
                        var themePublication = xmlMapper.readValue(xr, ThemePublication.class);
                        log.debug("Identifier: " + themePublication.getIdentifier());

                        // Verwenden wir später, um aus sämtlichen Collections einen Catalog zu machen.
                        collections.add(themePublication.getIdentifier());

                        BoundingBox bboxWGS = GeometryTransformation.convertBboxToWGS(themePublication.getBbox());
                        themePublication.setBbox(bboxWGS);

                        var itemsList = new ArrayList<Item>();
                        for (Item item : themePublication.getItems()) {
                            BoundingBox itemBboxWGS = GeometryTransformation.convertBboxToWGS(item.getBbox());
                            item.setBbox(itemBboxWGS);

                            var geom = wktReader.read(item.getGeometry());
                            var geomWGS = GeometryTransformation.convertGeometryToWGS(geom);
                            geoJsonWriter.setEncodeCRS(false);
                            var geomGeoJson = geoJsonWriter.write(geomWGS);
                            item.setGeometry(geomGeoJson);

                            itemsList.add(item);
                        }
                        themePublication.setItems(itemsList);

                        stacCreator.create(stacDirectory, themePublication, filesServerUrl, rootHref);
                    }
                }
            }
            
            stacCreator.create_catalog(stacDirectory, collections, rootHref);
        }
    }    
}
