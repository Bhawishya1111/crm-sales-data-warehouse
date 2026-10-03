import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import javax.xml.parsers.*;
import org.w3c.dom.*;
import org.xml.sax.SAXException;

/**
 * EXTRACT: reads an XML file into a list of rows. Each direct child of the root element is
 * one record and each child element of a record is one field. This works for both
 * accounts.xml (root <accounts>, records <account>) and sales_teams.xml (root <root>, records <row>).
 * Note accounts.xml has a field named <account> nested inside a record named <account>, so
 * we walk direct children rather than using getElementsByTagName.
 */
public class XMLParser {
    public static List<Map<String, String>> parse(Path file)
            throws IOException, ParserConfigurationException, SAXException {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); // XXE hardening
        Document doc = f.newDocumentBuilder().parse(file.toFile());
        List<Map<String, String>> rows = new ArrayList<>();
        for (Node rec = doc.getDocumentElement().getFirstChild(); rec != null; rec = rec.getNextSibling()) {
            if (rec.getNodeType() != Node.ELEMENT_NODE) continue;
            Map<String, String> row = new LinkedHashMap<>();
            for (Node fld = rec.getFirstChild(); fld != null; fld = fld.getNextSibling())
                if (fld.getNodeType() == Node.ELEMENT_NODE)
                    row.put(fld.getNodeName(), fld.getTextContent());
            rows.add(row);
        }
        return rows;
    }
}
