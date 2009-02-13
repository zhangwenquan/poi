/* ====================================================================
   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to You under the Apache License, Version 2.0
   (the "License"); you may not use this file except in compliance with
   the License.  You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
==================================================================== */

package org.apache.poi.xssf.usermodel;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import javax.xml.namespace.QName;

import org.apache.poi.POIXMLDocument;
import org.apache.poi.POIXMLDocumentPart;
import org.apache.poi.POIXMLException;
import org.apache.poi.hssf.record.formula.SheetNameFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.Row.MissingCellPolicy;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.util.IOUtils;
import org.apache.poi.util.POILogFactory;
import org.apache.poi.util.POILogger;
import org.apache.poi.util.PackageHelper;
import org.apache.poi.xssf.model.SharedStringsTable;
import org.apache.poi.xssf.model.StylesTable;
import org.apache.poi.xssf.model.CalculationChain;
import org.apache.xmlbeans.XmlException;
import org.apache.xmlbeans.XmlObject;
import org.apache.xmlbeans.XmlOptions;
import org.apache.poi.openxml4j.exceptions.OpenXML4JException;
import org.apache.poi.openxml4j.opc.Package;
import org.apache.poi.openxml4j.opc.PackagePart;
import org.apache.poi.openxml4j.opc.PackagePartName;
import org.apache.poi.openxml4j.opc.PackageRelationship;
import org.apache.poi.openxml4j.opc.PackageRelationshipTypes;
import org.apache.poi.openxml4j.opc.PackagingURIHelper;
import org.apache.poi.openxml4j.opc.TargetMode;
import org.openxmlformats.schemas.officeDocument.x2006.relationships.STRelationshipId;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTBookView;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTBookViews;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTDefinedName;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTDefinedNames;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTDialogsheet;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTSheet;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTWorkbook;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTWorkbookPr;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTWorksheet;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.STSheetState;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.WorkbookDocument;

/**
 * High level representation of a SpreadsheetML workbook.  This is the first object most users
 * will construct whether they are reading or writing a workbook.  It is also the
 * top level object for creating new sheets/etc.
 */
public class XSSFWorkbook extends POIXMLDocument implements Workbook, Iterable<XSSFSheet> {
    private static final Pattern COMMA_PATTERN = Pattern.compile(",");

    /**
     * Width of one character of the default font in pixels. Same for Calibry and Arial.
     */
    public static final float DEFAULT_CHARACTER_WIDTH = 7.0017f;

    /**
     * The underlying XML bean
     */
    private CTWorkbook workbook;

    /**
     * this holds the XSSFSheet objects attached to this workbook
     */
    private List<XSSFSheet> sheets;

    /**
     * this holds the XSSFName objects attached to this workbook
     */
    private List<XSSFName> namedRanges;

    /**
     * shared string table - a cache of strings in this workbook
     */
    private SharedStringsTable sharedStringSource;

    /**
     * A collection of shared objects used for styling content,
     * e.g. fonts, cell styles, colors, etc.
     */
    private StylesTable stylesSource;

    /**
     * TODO
     */
    private CalculationChain calcChain;

    /**
     * Used to keep track of the data formatter so that all
     * createDataFormatter calls return the same one for a given
     * book.  This ensures that updates from one places is visible
     * someplace else.
     */
    private XSSFDataFormat formatter;

    /**
     * The policy to apply in the event of missing or
     *  blank cells when fetching from a row.
     * See {@link org.apache.poi.ss.usermodel.Row.MissingCellPolicy}
     */
    private MissingCellPolicy _missingCellPolicy = Row.RETURN_NULL_AND_BLANK;

    /**
     * array of pictures for this workbook
     */
    private List<XSSFPictureData> pictures;

    private static POILogger logger = POILogFactory.getLogger(XSSFWorkbook.class);

    /**
     * Create a new SpreadsheetML workbook.
     */
    public XSSFWorkbook() {
        super(newPackage());
        onWorkbookCreate();
    }

    /**
     * Constructs a XSSFWorkbook object given a OpenXML4J <code>Package</code> object,
     * see <a href="http://openxml4j.org/">www.openxml4j.org</a>.
     *
     * @param pkg the OpenXML4J <code>Package</code> object.
     */
    public XSSFWorkbook(Package pkg) throws IOException {
        super(ensureWriteAccess(pkg));

        //build a tree of POIXMLDocumentParts, this workbook being the root
        try {
            read(XSSFFactory.getInstance());
        } catch (OpenXML4JException e){
            throw new POIXMLException(e);
        }
        onDocumentRead();
    }

    /**
     * Constructs a XSSFWorkbook object given a file name.
     *
     * @param      path   the file name.
     */
    public XSSFWorkbook(String path) throws IOException {
        this(openPackage(path));
    }

    @Override
    protected void onDocumentRead() throws IOException {
        try {
            WorkbookDocument doc = WorkbookDocument.Factory.parse(getPackagePart().getInputStream());
            this.workbook = doc.getWorkbook();

            Map<String, XSSFSheet> shIdMap = new HashMap<String, XSSFSheet>();
            for(POIXMLDocumentPart p : getRelations()){
                if(p instanceof SharedStringsTable) sharedStringSource = (SharedStringsTable)p;
                else if(p instanceof StylesTable) stylesSource = (StylesTable)p;
                else if(p instanceof CalculationChain) calcChain = (CalculationChain)p;
                else if (p instanceof XSSFSheet) {
                    shIdMap.put(p.getPackageRelationship().getId(), (XSSFSheet)p);
                }
            }

            // Load individual sheets. The order of sheets is defined by the order of CTSheet elements in the workbook
            sheets = new ArrayList<XSSFSheet>(shIdMap.size());
            for (CTSheet ctSheet : this.workbook.getSheets().getSheetArray()) {
                XSSFSheet sh = shIdMap.get(ctSheet.getId());
                if(sh == null) {
                    logger.log(POILogger.WARN, "Sheet with name " + ctSheet.getName() + " and r:id " + ctSheet.getId()+ " was defined, but didn't exist in package, skipping");
                    continue;
                }
                sh.sheet = ctSheet;
                sh.onDocumentRead();
                sheets.add(sh);
            }

            if(sharedStringSource == null) {
                //Create SST if it is missing
                sharedStringSource = (SharedStringsTable)createRelationship(XSSFRelation.SHARED_STRINGS, XSSFFactory.getInstance());
            }

            // Process the named ranges
            namedRanges = new ArrayList<XSSFName>();
            if(workbook.isSetDefinedNames()) {
                for(CTDefinedName ctName : workbook.getDefinedNames().getDefinedNameArray()) {
                    namedRanges.add(new XSSFName(ctName, this));
                }
            }

        } catch (XmlException e) {
            throw new POIXMLException(e);
        }
    }

    /**
     * Create a new CTWorkbook with all values set to default
     */
    private void onWorkbookCreate() {
        workbook = CTWorkbook.Factory.newInstance();
        CTBookViews bvs = workbook.addNewBookViews();
        CTBookView bv = bvs.addNewWorkbookView();
        bv.setActiveTab(0);
        workbook.addNewSheets();

        sharedStringSource = (SharedStringsTable)createRelationship(XSSFRelation.SHARED_STRINGS, XSSFFactory.getInstance());
        stylesSource = (StylesTable)createRelationship(XSSFRelation.STYLES, XSSFFactory.getInstance());

        namedRanges = new ArrayList<XSSFName>();
        sheets = new ArrayList<XSSFSheet>();
    }

    /**
     * Create a new SpreadsheetML package and setup the default minimal content
     */
    protected static Package newPackage() {
        try {
            Package pkg = Package.create(PackageHelper.createTempFile());
            // Main part
            PackagePartName corePartName = PackagingURIHelper.createPartName(XSSFRelation.WORKBOOK.getDefaultFileName());
            // Create main part relationship
            pkg.addRelationship(corePartName, TargetMode.INTERNAL, PackageRelationshipTypes.CORE_DOCUMENT);
            // Create main document part
            pkg.createPart(corePartName, XSSFRelation.WORKBOOK.getContentType());

            pkg.getPackageProperties().setCreatorProperty("Apache POI");

            return pkg;
        } catch (Exception e){
            throw new POIXMLException(e);
        }
    }

    /**
     * Return the underlying XML bean
     *
     * @return the underlying CTWorkbook bean
     */
    public CTWorkbook getCTWorkbook() {
        return this.workbook;
    }

    /**
     * Adds a picture to the workbook.
     *
     * @param pictureData       The bytes of the picture
     * @param format            The format of the picture.
     *
     * @return the index to this picture (0 based), the added picture can be obtained from {@link #getAllPictures()} .
     * @see Workbook#PICTURE_TYPE_EMF
     * @see Workbook#PICTURE_TYPE_WMF
     * @see Workbook#PICTURE_TYPE_PICT
     * @see Workbook#PICTURE_TYPE_JPEG
     * @see Workbook#PICTURE_TYPE_PNG
     * @see Workbook#PICTURE_TYPE_DIB
     * @see #getAllPictures()
     */
    public int addPicture(byte[] pictureData, int format) {
        int imageNumber = getAllPictures().size() + 1;
        XSSFPictureData img = (XSSFPictureData)createRelationship(XSSFPictureData.RELATIONS[format], XSSFFactory.getInstance(), imageNumber, true);
        try {
            OutputStream out = img.getPackagePart().getOutputStream();
            out.write(pictureData);
            out.close();
        } catch (IOException e){
            throw new POIXMLException(e);
        }
        pictures.add(img);
        return imageNumber - 1;
    }

    /**
     * Adds a picture to the workbook.
     *
     * @param is                The sream to read image from
     * @param format            The format of the picture.
     *
     * @return the index to this picture (0 based), the added picture can be obtained from {@link #getAllPictures()} .
     * @see Workbook#PICTURE_TYPE_EMF
     * @see Workbook#PICTURE_TYPE_WMF
     * @see Workbook#PICTURE_TYPE_PICT
     * @see Workbook#PICTURE_TYPE_JPEG
     * @see Workbook#PICTURE_TYPE_PNG
     * @see Workbook#PICTURE_TYPE_DIB
     * @see #getAllPictures()
     */
    public int addPicture(InputStream is, int format) throws IOException {
        int imageNumber = getAllPictures().size() + 1;
        XSSFPictureData img = (XSSFPictureData)createRelationship(XSSFPictureData.RELATIONS[format], XSSFFactory.getInstance(), imageNumber, true);
        OutputStream out = img.getPackagePart().getOutputStream();
        IOUtils.copy(is, out);
        out.close();
        pictures.add(img);
        return imageNumber - 1;
    }

    /**
     * Create an XSSFSheet from an existing sheet in the XSSFWorkbook.
     *  The cloned sheet is a deep copy of the original.
     *
     * @return XSSFSheet representing the cloned sheet.
     * @throws IllegalArgumentException if the sheet index in invalid
     * @throws POIXMLException if there were errors when cloning
     */
    public XSSFSheet cloneSheet(int sheetNum) {
        validateSheetIndex(sheetNum);

        XSSFSheet srcSheet = sheets.get(sheetNum);
        String srcName = srcSheet.getSheetName();
        String clonedName = getUniqueSheetName(srcName);

        XSSFSheet clonedSheet = createSheet(clonedName);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            srcSheet.write(out);
            clonedSheet.read(new ByteArrayInputStream(out.toByteArray()));
        } catch (IOException e){
            throw new POIXMLException("Failed to clone sheet", e);
        }
        CTWorksheet ct = clonedSheet.getCTWorksheet();
        if(ct.isSetDrawing()) {
            logger.log(POILogger.WARN, "Cloning sheets with drawings is not yet supported.");
            ct.unsetDrawing();
        }
        if(ct.isSetLegacyDrawing()) {
            logger.log(POILogger.WARN, "Cloning sheets with comments is not yet supported.");
            ct.unsetLegacyDrawing();
        }

        clonedSheet.setSelected(false);
        return clonedSheet;
    }

    /**
     * Generate a valid sheet name based on the existing one. Used when cloning sheets.
     *
     * @param srcName the original sheet name to
     * @return clone sheet name
     */
    private String getUniqueSheetName(String srcName) {
        int uniqueIndex = 2;
        String baseName = srcName;
        int bracketPos = srcName.lastIndexOf('(');
        if (bracketPos > 0 && srcName.endsWith(")")) {
            String suffix = srcName.substring(bracketPos + 1, srcName.length() - ")".length());
            try {
                uniqueIndex = Integer.parseInt(suffix.trim());
                uniqueIndex++;
                baseName = srcName.substring(0, bracketPos).trim();
            } catch (NumberFormatException e) {
                // contents of brackets not numeric
            }
        }
        while (true) {
            // Try and find the next sheet name that is unique
            String index = Integer.toString(uniqueIndex++);
            String name;
            if (baseName.length() + index.length() + 2 < 31) {
                name = baseName + " (" + index + ")";
            } else {
                name = baseName.substring(0, 31 - index.length() - 2) + "(" + index + ")";
            }

            //If the sheet name is unique, then set it otherwise move on to the next number.
            if (getSheetIndex(name) == -1) {
                return name;
            }
        }
    }

    /**
     * Create a new XSSFCellStyle and add it to the workbook's style table
     *
     * @return the new XSSFCellStyle object
     */
    public XSSFCellStyle createCellStyle() {
        return stylesSource.createCellStyle();
    }

    /**
     * Returns the instance of XSSFDataFormat for this workbook.
     *
     * @return the XSSFDataFormat object
     * @see org.apache.poi.ss.usermodel.DataFormat
     */
    public XSSFDataFormat createDataFormat() {
        if (formatter == null)
            formatter = new XSSFDataFormat(stylesSource);
        return formatter;
    }

    /**
     * Create a new Font and add it to the workbook's font table
     *
     * @return new font object
     */
    public XSSFFont createFont() {
        XSSFFont font = new XSSFFont();
        font.putFont(stylesSource);
        return font;
    }

    /**
     * Create a new named range and add it to the workbook's names table
     *
     * @return named range high level
     */
    public XSSFName createName() {
        XSSFName name = new XSSFName(CTDefinedName.Factory.newInstance(), this);
        namedRanges.add(name);
        return name;
    }

    /**
     * Create an XSSFSheet for this workbook, adds it to the sheets and returns
     * the high level representation.  Use this to create new sheets.
     *
     * @return XSSFSheet representing the new sheet.
     */
    public XSSFSheet createSheet() {
        String sheetname = "Sheet" + (sheets.size() + 1);
        return createSheet(sheetname);
    }

    /**
     * Create an XSSFSheet for this workbook, adds it to the sheets and returns
     * the high level representation.  Use this to create new sheets.
     *
     * @param sheetname  sheetname to set for the sheet, can't be duplicate, greater than 31 chars or contain /\?*[]
     * @return XSSFSheet representing the new sheet.
     * @throws IllegalArgumentException if the sheetname is invalid or the workbook already contains a sheet of this name
     */
    public XSSFSheet createSheet(String sheetname) {
        if (containsSheet( sheetname, sheets.size() ))
               throw new IllegalArgumentException( "The workbook already contains a sheet of this name");

        CTSheet sheet = addSheet(sheetname);

        int sheetNumber = getNumberOfSheets() + 1;
        XSSFSheet wrapper = (XSSFSheet)createRelationship(XSSFRelation.WORKSHEET, XSSFFactory.getInstance(), sheetNumber);
        wrapper.sheet = sheet;
        sheet.setId(wrapper.getPackageRelationship().getId());
        sheet.setSheetId(sheetNumber);
        if(sheets.size() == 0) wrapper.setSelected(true);
        sheets.add(wrapper);
        return wrapper;
    }

    protected XSSFDialogsheet createDialogsheet(String sheetname, CTDialogsheet dialogsheet) {
        XSSFSheet sheet = createSheet(sheetname);
        return new XSSFDialogsheet(sheet);
    }

    private CTSheet addSheet(String sheetname) {
        validateSheetName(sheetname);

        CTSheet sheet = workbook.getSheets().addNewSheet();
        sheet.setName(sheetname);
        return sheet;
    }

    /**
     * Finds a font that matches the one with the supplied attributes
     */
    public XSSFFont findFont(short boldWeight, short color, short fontHeight, String name, boolean italic, boolean strikeout, short typeOffset, byte underline) {
        return stylesSource.findFont(boldWeight, color, fontHeight, name, italic, strikeout, typeOffset, underline);
    }

    /**
     * Convenience method to get the active sheet.  The active sheet is is the sheet
     * which is currently displayed when the workbook is viewed in Excel.
     * 'Selected' sheet(s) is a distinct concept.
     */
    public int getActiveSheetIndex() {
        //activeTab (Active Sheet Index) Specifies an unsignedInt
        //that contains the index to the active sheet in this book view.
        return (int)workbook.getBookViews().getWorkbookViewArray(0).getActiveTab();
    }

    /**
     * Gets all pictures from the Workbook.
     *
     * @return the list of pictures (a list of {@link XSSFPictureData} objects.)
     * @see #addPicture(byte[], int)
     */
    public List<XSSFPictureData> getAllPictures() {
        if(pictures == null) {
            //In OOXML pictures are referred to in sheets,
            //dive into sheet's relations, select drawings and their images
            pictures = new ArrayList<XSSFPictureData>();
            for(XSSFSheet sh : sheets){
                for(POIXMLDocumentPart dr : sh.getRelations()){
                    if(dr instanceof XSSFDrawing){
                        for(POIXMLDocumentPart img : dr.getRelations()){
                            if(img instanceof XSSFPictureData){
                                pictures.add((XSSFPictureData)img);
                            }
                        }
                    }
                }
            }
        }
        return pictures;
    }

    /**
     * gGet the cell style object at the given index
     *
     * @param idx  index within the set of styles
     * @return XSSFCellStyle object at the index
     */
    public XSSFCellStyle getCellStyleAt(short idx) {
        return stylesSource.getStyleAt(idx);
    }

    /**
     * Get the font at the given index number
     *
     * @param idx  index number
     * @return XSSFFont at the index
     */
    public XSSFFont getFontAt(short idx) {
        return stylesSource.getFontAt(idx);
    }

    /**
     * Gets the Named range at the given index number
     *
     * @param index position of the named range
     * @return XSSFName at the index
     */
    public XSSFName getNameAt(int index) {
        return namedRanges.get(index);
    }

    /**
     * Gets the named range index by his name
     * <i>Note:</i>Excel named ranges are case-insensitive and
     * this method performs a case-insensitive search.
     *
     * @param name named range name
     * @return named range index
     */
    public int getNameIndex(String name) {
        int i = 0;
        for(XSSFName nr : namedRanges) {
            if(nr.getNameName().equals(name)) {
                return i;
            }
            i++;
        }
        return -1;
    }

    /**
     * Get the number of styles the workbook contains
     *
     * @return count of cell styles
     */
    public short getNumCellStyles() {
        return (short) (stylesSource).getNumCellStyles();
    }

    /**
     * Get the number of fonts in the this workbook
     *
     * @return number of fonts
     */
    public short getNumberOfFonts() {
        return (short)stylesSource.getFonts().size();
    }

    /**
     * Get the number of named ranges in the this workbook
     *
     * @return number of named ranges
     */
    public int getNumberOfNames() {
        return namedRanges.size();
    }

    /**
     * Get the number of worksheets in the this workbook
     *
     * @return number of worksheets
     */
    public int getNumberOfSheets() {
        return sheets.size();
    }

    /**
     * Retrieves the reference for the printarea of the specified sheet, the sheet name is appended to the reference even if it was not specified.
     * @param sheetIndex Zero-based sheet index (0 Represents the first sheet to keep consistent with java)
     * @return String Null if no print area has been defined
     */
    public String getPrintArea(int sheetIndex) {	
        XSSFName name = getBuiltInName(XSSFName.BUILTIN_PRINT_AREA, sheetIndex);
        if (name == null) return null;
        //adding one here because 0 indicates a global named region; doesnt make sense for print areas
        return name.getRefersToFormula();

    }

    /**
     * Get sheet with the given name (case insensitive match)
     *
     * @param name of the sheet
     * @return XSSFSheet with the name provided or <code>null</code> if it does not exist
     */
    public XSSFSheet getSheet(String name) {
        for (XSSFSheet sheet : sheets) {
            if (name.equalsIgnoreCase(sheet.getSheetName())) {
                return sheet;
            }
        }
        return null;
    }

    /**
     * Get the XSSFSheet object at the given index.
     *
     * @param index of the sheet number (0-based physical & logical)
     * @return XSSFSheet at the provided index
     * @throws IllegalArgumentException if the index is out of range (index
     *            &lt; 0 || index &gt;= getNumberOfSheets()).
     */
    public XSSFSheet getSheetAt(int index) {
        validateSheetIndex(index);
        return sheets.get(index);
    }

    /**
     * Returns the index of the sheet by his name (case insensitive match)
     *
     * @param name the sheet name
     * @return index of the sheet (0 based) or <tt>-1</tt if not found
     */
    public int getSheetIndex(String name) {
        for (int i = 0 ; i < sheets.size() ; ++i) {
            XSSFSheet sheet = sheets.get(i);
            if (name.equalsIgnoreCase(sheet.getSheetName())) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Returns the index of the given sheet
     *
     * @param sheet the sheet to look up
     * @return index of the sheet (0 based). <tt>-1</tt> if not found
     */
    public int getSheetIndex(Sheet sheet) {
        int idx = 0;
        for(XSSFSheet sh : sheets){
            if(sh == sheet) return idx;
            idx++;
        }
        return -1;
    }

    /**
     * Get the sheet name
     *
     * @param sheetIx Number
     * @return Sheet name
     */
    public String getSheetName(int sheetIx) {
        validateSheetIndex(sheetIx);
        return sheets.get(sheetIx).getSheetName();
    }

    /**
     * Allows foreach loops:
     * <pre><code>
     * XSSFWorkbook wb = new XSSFWorkbook(package);
     * for(XSSFSheet sheet : wb){
     *
     * }
     * </code></pre>
     */
    public Iterator<XSSFSheet> iterator() {
        return sheets.iterator();
    }
    /**
     * Are we a normal workbook (.xlsx), or a
     *  macro enabled workbook (.xlsm)?
     */
    public boolean isMacroEnabled() {
        return getPackagePart().getContentType().equals(XSSFRelation.MACROS_WORKBOOK.getContentType());
    }

    /**
     * Removes the name by its index
     *
     * @param nameIndex 0-based index of the name to remove.
     */
    public void removeName(int nameIndex) {
        namedRanges.remove(nameIndex);
    }

    /**
     * Remove the named range by its name
     *
     * @param name named range name
     * @throws IllegalArgumentException if the name was not found
     */
    public void removeName(String name) {
        for (int i = 0; i < namedRanges.size(); i++) {
            XSSFName nm = namedRanges.get(i);
            if(nm.getNameName().equalsIgnoreCase(name)) {
                removeName(i);
                return;
            }
        }
        throw new IllegalArgumentException("Named range was not found: " + name);
    }

    /**
     * Delete the printarea for the sheet specified
     *
     * @param sheetIndex 0-based sheet index (0 = First Sheet)
     */
    public void removePrintArea(int sheetIndex) {
        int cont = 0;
        for (XSSFName name : namedRanges) {
            if (name.getNameName().equals(XSSFName.BUILTIN_PRINT_AREA) && name.getSheetIndex() == sheetIndex) {
                namedRanges.remove(cont);
                break;
            }
            cont++;
        }
    }

    /**
     * Removes sheet at the given index.<p/>
     *
     * Care must be taken if the removed sheet is the currently active or only selected sheet in
     * the workbook. There are a few situations when Excel must have a selection and/or active
     * sheet. (For example when printing - see Bug 40414).<br/>
     *
     * This method makes sure that if the removed sheet was active, another sheet will become
     * active in its place.  Furthermore, if the removed sheet was the only selected sheet, another
     * sheet will become selected.  The newly active/selected sheet will have the same index, or
     * one less if the removed sheet was the last in the workbook.
     *
     * @param index of the sheet  (0-based)
     */
    public void removeSheetAt(int index) {
        validateSheetIndex(index);

        this.sheets.remove(index);
        this.workbook.getSheets().removeSheet(index);
    }

    /**
     * Retrieves the current policy on what to do when
     *  getting missing or blank cells from a row.
     * The default is to return blank and null cells.
     *  {@link MissingCellPolicy}
     */
    public MissingCellPolicy getMissingCellPolicy() {
        return _missingCellPolicy;
    }
    /**
     * Sets the policy on what to do when
     *  getting missing or blank cells from a row.
     * This will then apply to all calls to
     *  {@link Row#getCell(int)}}. See
     *  {@link MissingCellPolicy}
     */
    public void setMissingCellPolicy(MissingCellPolicy missingCellPolicy) {
        _missingCellPolicy = missingCellPolicy;
    }

    /**
     * Convenience method to set the active sheet.  The active sheet is is the sheet
     * which is currently displayed when the workbook is viewed in Excel.
     * 'Selected' sheet(s) is a distinct concept.
     */
    public void setActiveSheet(int index) {

        validateSheetIndex(index);
        //activeTab (Active Sheet Index) Specifies an unsignedInt that contains the index to the active sheet in this book view.
        CTBookView[] arrayBook = workbook.getBookViews().getWorkbookViewArray();
        for (int i = 0; i < arrayBook.length; i++) {
            workbook.getBookViews().getWorkbookViewArray(i).setActiveTab(index);
        }
    }

    /**
     * Validate sheet index
     *
     * @param index the index to validate
     * @throws IllegalArgumentException if the index is out of range (index
     *            &lt; 0 || index &gt;= getNumberOfSheets()).
    */
    private void validateSheetIndex(int index) {
        int lastSheetIx = sheets.size() - 1;
        if (index < 0 || index > lastSheetIx) {
            throw new IllegalArgumentException("Sheet index ("
                    + index +") is out of range (0.." +	lastSheetIx + ")");
        }
    }

    /**
     * Gets the first tab that is displayed in the list of tabs in excel.
     *
     * @return integer that contains the index to the active sheet in this book view.
     */
    public int getFirstVisibleTab() {
        CTBookViews bookViews = workbook.getBookViews();
        CTBookView bookView = bookViews.getWorkbookViewArray(0);
        return (short) bookView.getActiveTab();
    }

    /**
     * Sets the first tab that is displayed in the list of tabs in excel.
     *
     * @param index integer that contains the index to the active sheet in this book view.
     */
    public void setFirstVisibleTab(int index) {
        CTBookViews bookViews = workbook.getBookViews();
        CTBookView bookView= bookViews.getWorkbookViewArray(0);
        bookView.setActiveTab(index);
    }

    /**
     * Sets the printarea for the sheet provided
     * <p>
     * i.e. Reference = $A$1:$B$2
     * @param sheetIndex Zero-based sheet index (0 Represents the first sheet to keep consistent with java)
     * @param reference Valid name Reference for the Print Area
     */
    public void setPrintArea(int sheetIndex, String reference) {
        XSSFName name = getBuiltInName(XSSFName.BUILTIN_PRINT_AREA, sheetIndex);
        if (name == null) {
            name = createBuiltInName(XSSFName.BUILTIN_PRINT_AREA, sheetIndex);
            namedRanges.add(name);
        }
        //short externSheetIndex = getWorkbook().checkExternSheet(sheetIndex);
        //name.setExternSheetNumber(externSheetIndex);
        String[] parts = COMMA_PATTERN.split(reference);
        StringBuffer sb = new StringBuffer(32);
        for (int i = 0; i < parts.length; i++) {
            if(i>0) {
                sb.append(",");
            }
            SheetNameFormatter.appendFormat(sb, getSheetName(sheetIndex));
            sb.append("!");
            sb.append(parts[i]);
        }
        name.setRefersToFormula(sb.toString());
    }

    /**
     * For the Convenience of Java Programmers maintaining pointers.
     * @see #setPrintArea(int, String)
     * @param sheetIndex Zero-based sheet index (0 = First Sheet)
     * @param startColumn Column to begin printarea
     * @param endColumn Column to end the printarea
     * @param startRow Row to begin the printarea
     * @param endRow Row to end the printarea
     */
    public void setPrintArea(int sheetIndex, int startColumn, int endColumn, int startRow, int endRow) {
        String reference=getReferencePrintArea(getSheetName(sheetIndex), startColumn, endColumn, startRow, endRow);
        setPrintArea(sheetIndex, reference);
    }


    /**
     * Sets the repeating rows and columns for a sheet.
     * <p/>
     * To set just repeating columns:
     * <pre>
     *  workbook.setRepeatingRowsAndColumns(0,0,1,-1,-1);
     * </pre>
     * To set just repeating rows:
     * <pre>
     *  workbook.setRepeatingRowsAndColumns(0,-1,-1,0,4);
     * </pre>
     * To remove all repeating rows and columns for a sheet.
     * <pre>
     *  workbook.setRepeatingRowsAndColumns(0,-1,-1,-1,-1);
     * </pre>
     *
     * @param sheetIndex  0 based index to sheet.
     * @param startColumn 0 based start of repeating columns.
     * @param endColumn   0 based end of repeating columns.
     * @param startRow    0 based start of repeating rows.
     * @param endRow      0 based end of repeating rows.
     */
    public void setRepeatingRowsAndColumns(int sheetIndex,
                                           int startColumn, int endColumn,
                                           int startRow, int endRow) {
        //	Check arguments
        if ((startColumn == -1 && endColumn != -1) || startColumn < -1 || endColumn < -1 || startColumn > endColumn)
            throw new IllegalArgumentException("Invalid column range specification");
        if ((startRow == -1 && endRow != -1) || startRow < -1 || endRow < -1 || startRow > endRow)
            throw new IllegalArgumentException("Invalid row range specification");

        XSSFSheet sheet = getSheetAt(sheetIndex);
        boolean removingRange = startColumn == -1 && endColumn == -1 && startRow == -1 && endRow == -1;

        XSSFName name = getBuiltInName(XSSFName.BUILTIN_PRINT_TITLE, sheetIndex);
        if (removingRange && name != null) {
            namedRanges.remove(name);
            return;
        }
        if (name == null) {
            name = createBuiltInName(XSSFName.BUILTIN_PRINT_TITLE, sheetIndex);
            String reference = getReferenceBuiltInRecord(name.getSheetName(), startColumn, endColumn, startRow, endRow);
            name.setRefersToFormula(reference);
            namedRanges.add(name);
        }

        XSSFPrintSetup printSetup = sheet.getPrintSetup();
        printSetup.setValidSettings(false);
    }

    private static String getReferenceBuiltInRecord(String sheetName, int startC, int endC, int startR, int endR) {
        //windows excel example for built-in title: 'second sheet'!$E:$F,'second sheet'!$2:$3
        CellReference colRef = new CellReference(sheetName, 0, startC, true, true);
        CellReference colRef2 = new CellReference(sheetName, 0, endC, true, true);

        String c = "'" + sheetName + "'!$" + colRef.getCellRefParts()[2] + ":$" + colRef2.getCellRefParts()[2];

        CellReference rowRef = new CellReference(sheetName, startR, 0, true, true);
        CellReference rowRef2 = new CellReference(sheetName, endR, 0, true, true);

        String r = "";

        if (!rowRef.getCellRefParts()[1].equals("0") && !rowRef2.getCellRefParts()[1].equals("0")) {
            r = ",'" + sheetName + "'!$" + rowRef.getCellRefParts()[1] + ":$" + rowRef2.getCellRefParts()[1];
        }
        return c + r;
    }

    private static String getReferencePrintArea(String sheetName, int startC, int endC, int startR, int endR) {
        //windows excel example: Sheet1!$C$3:$E$4
        CellReference colRef = new CellReference(sheetName, startR, startC, true, true);
        CellReference colRef2 = new CellReference(sheetName, endR, endC, true, true);

        return "$" + colRef.getCellRefParts()[2] + "$" + colRef.getCellRefParts()[1] + ":$" + colRef2.getCellRefParts()[2] + "$" + colRef2.getCellRefParts()[1];
    }

    private XSSFName getBuiltInName(String builtInCode, int sheetNumber) {
        for (XSSFName name : namedRanges) {
            if (name.getNameName().equalsIgnoreCase(builtInCode) && name.getSheetIndex() == sheetNumber) {
                return name;
            }
        }
        return null;
    }

    /**
     * Generates a NameRecord to represent a built-in region
     *
     * @return a new NameRecord
     * @throws IllegalArgumentException if sheetNumber is invalid
     * @throws POIXMLException if such a name already exists in the workbook
     */
    private XSSFName createBuiltInName(String builtInName, int sheetNumber) {
        validateSheetIndex(sheetNumber);

        CTDefinedNames names = workbook.getDefinedNames() == null ? workbook.addNewDefinedNames() : workbook.getDefinedNames();
        CTDefinedName nameRecord = names.addNewDefinedName();
        nameRecord.setName(builtInName);
        nameRecord.setLocalSheetId(sheetNumber);

        XSSFName name = new XSSFName(nameRecord, this);
        for (XSSFName nr : namedRanges) {
            if (nr.equals(name))
                throw new POIXMLException("Builtin (" + builtInName
                        + ") already exists for sheet (" + sheetNumber + ")");
        }

        return name;
    }

    /**
     * We only set one sheet as selected for compatibility with HSSF.
     */
    public void setSelectedTab(short index) {
        for (int i = 0 ; i < sheets.size() ; ++i) {
            XSSFSheet sheet = sheets.get(i);
            sheet.setSelected(i == index);
        }
    }

    /**
     * Set the sheet name.
     * Will throw IllegalArgumentException if the name is greater than 31 chars
     * or contains /\?*[]
     *
     * @param sheet number (0 based)
     * @see #validateSheetName(String)
     */
    public void setSheetName(int sheet, String name) {
        validateSheetIndex(sheet);
        validateSheetName(name);
        if (containsSheet(name, sheet ))
            throw new IllegalArgumentException( "The workbook already contains a sheet of this name" );
        workbook.getSheets().getSheetArray(sheet).setName(name);
    }

    /**
     * sets the order of appearance for a given sheet.
     *
     * @param sheetname the name of the sheet to reorder
     * @param pos the position that we want to insert the sheet into (0 based)
     */
    public void setSheetOrder(String sheetname, int pos) {
        int idx = getSheetIndex(sheetname);
        sheets.add(pos, sheets.remove(idx));
        // Reorder CTSheets
        XmlObject cts = workbook.getSheets().getSheetArray(idx).copy();
        workbook.getSheets().removeSheet(idx);
        CTSheet newcts = workbook.getSheets().insertNewSheet(pos);
        newcts.set(cts);
    }

    /**
     * marshal named ranges from the {@link #namedRanges} collection to the underlying CTWorkbook bean
     */
    private void saveNamedRanges(){
        // Named ranges
        if(namedRanges.size() > 0) {
            CTDefinedNames names = CTDefinedNames.Factory.newInstance();
            CTDefinedName[] nr = new CTDefinedName[namedRanges.size()];
            int i = 0;
            for(XSSFName name : namedRanges) {
                nr[i] = name.getCTName();
                i++;
            }
            names.setDefinedNameArray(nr);
            workbook.setDefinedNames(names);
        } else {
            if(workbook.isSetDefinedNames()) {
                workbook.unsetDefinedNames();
            }
        }

    }

    @Override
    protected void commit() throws IOException {
        saveNamedRanges();

        XmlOptions xmlOptions = new XmlOptions(DEFAULT_XML_OPTIONS);
        xmlOptions.setSaveSyntheticDocumentElement(new QName(CTWorkbook.type.getName().getNamespaceURI(), "workbook"));
        Map<String, String> map = new HashMap<String, String>();
        map.put(STRelationshipId.type.getName().getNamespaceURI(), "r");
        xmlOptions.setSaveSuggestedPrefixes(map);

        PackagePart part = getPackagePart();
        OutputStream out = part.getOutputStream();
        workbook.save(out, xmlOptions);
        out.close();
    }

    /**
     * Returns SharedStringsTable - tha cache of string for this workbook
     *
     * @return the shared string table
     */
    public SharedStringsTable getSharedStringSource() {
        return this.sharedStringSource;
    }

    /**
     * Return a object representing a collection of shared objects used for styling content,
     * e.g. fonts, cell styles, colors, etc.
     */
    public StylesTable getStylesSource() {
        return this.stylesSource;
    }

    /**
     * Returns an object that handles instantiating concrete
     *  classes of the various instances for XSSF.
     */
    public XSSFCreationHelper getCreationHelper() {
        return new XSSFCreationHelper(this);
    }

    /**
     * Determines whether a workbook contains the provided sheet name.
     *
     * @param name the name to test (case insensitive match)
     * @param excludeSheetIdx the sheet to exclude from the check or -1 to include all sheets in the check.
     * @return true if the sheet contains the name, false otherwise.
     */
    private boolean containsSheet(String name, int excludeSheetIdx) {
        CTSheet[] ctSheetArray = workbook.getSheets().getSheetArray();
        for (int i = 0; i < ctSheetArray.length; i++) {
            if (excludeSheetIdx != i && name.equalsIgnoreCase(ctSheetArray[i].getName()))
                return true;
        }
        return false;
    }

    /**
     * Validates sheet name.
     *
     * <p>
     * The character count <tt>MUST</tt> be greater than or equal to 1 and less than or equal to 31.
     * The string MUST NOT contain the any of the following characters:
     * <ul>
     * <li> 0x0000 </li>
     * <li> 0x0003 </li>
     * <li> colon (:) </li>
     * <li> backslash (\) </li>
     * <li> asterisk (*) </li>
     * <li> question mark (?) </li>
     * <li> forward slash (/) </li>
     * <li> opening square bracket ([) </li>
     * <li> closing square bracket (]) </li>
     * </ul>
     * The string MUST NOT begin or end with the single quote (') character.
     * </p>
     *
     * @param sheetName the name to validate
     */
    private static void validateSheetName(String sheetName) {
        if (sheetName == null) {
            throw new IllegalArgumentException("sheetName must not be null");
        }
        int len = sheetName.length();
        if (len < 1 || len > 31) {
            throw new IllegalArgumentException("sheetName '" + sheetName
                    + "' is invalid - must be 1-30 characters long");
        }
        for (int i=0; i<len; i++) {
            char ch = sheetName.charAt(i);
            switch (ch) {
                case '/':
                case '\\':
                case '?':
                case '*':
                case ']':
                case '[':
                    break;
                default:
                    // all other chars OK
                    continue;
            }
            throw new IllegalArgumentException("Invalid char (" + ch
                    + ") found at index (" + i + ") in sheet name '" + sheetName + "'");
        }
     }

    /**
     * Gets a boolean value that indicates whether the date systems used in the workbook starts in 1904.
     * <p>
     * The default value is false, meaning that the workbook uses the 1900 date system,
     * where 1/1/1900 is the first day in the system..
     * </p>
     * @return true if the date systems used in the workbook starts in 1904
     */
    protected boolean isDate1904(){
        CTWorkbookPr workbookPr = workbook.getWorkbookPr();
        return workbookPr != null && workbookPr.getDate1904();
    }

    /**
     * Get the document's embedded files.
     */
    public List<PackagePart> getAllEmbedds() throws OpenXML4JException {
        List<PackagePart> embedds = new LinkedList<PackagePart>();

        for(XSSFSheet sheet : sheets){
            // Get the embeddings for the workbook
            for(PackageRelationship rel : sheet.getPackagePart().getRelationshipsByType(XSSFRelation.OLEEMBEDDINGS.getRelation()))
                embedds.add(getTargetPart(rel));

            for(PackageRelationship rel : sheet.getPackagePart().getRelationshipsByType(XSSFRelation.PACKEMBEDDINGS.getRelation()))
                embedds.add(getTargetPart(rel));

        }
        return embedds;
    }
    
    public boolean isHidden() {
    	throw new RuntimeException("Not implemented yet");
    }

    public void setHidden(boolean hiddenFlag) {
    	throw new RuntimeException("Not implemented yet");
    }

    public boolean isSheetHidden(int sheetIx) {
        validateSheetIndex(sheetIx);
        CTSheet ctSheet = sheets.get(sheetIx).sheet;
        return ctSheet.getState() == STSheetState.HIDDEN;
    }

    public boolean isSheetVeryHidden(int sheetIx) {
        validateSheetIndex(sheetIx);
        CTSheet ctSheet = sheets.get(sheetIx).sheet;
        return ctSheet.getState() == STSheetState.VERY_HIDDEN;
    }

    public void setSheetHidden(int sheetIx, boolean hidden) {
        validateSheetIndex(sheetIx);
        CTSheet ctSheet = sheets.get(sheetIx).sheet;
        ctSheet.setState(hidden ? STSheetState.HIDDEN : STSheetState.VISIBLE);
    }

    public void setSheetHidden(int sheetIx, int hidden) {
        validateSheetIndex(sheetIx);
        CTSheet ctSheet = sheets.get(sheetIx).sheet;
        ctSheet.setState(STSheetState.Enum.forInt(hidden));
    }

    /**
     * Fired when a formula is deleted from this workbook,
     * for example when calling cell.setCellFormula(null)
     *
     * @see XSSFCell#setCellFormula(String) 
     */
    protected void onDeleteFormula(XSSFCell cell){
        if(calcChain != null) {
            int sheetId = (int)cell.getSheet().sheet.getSheetId();
            calcChain.removeItem(sheetId, cell.getReference());
        }
    }

    /**
     * Return the CalculationChain object for this workbook
     * <p>
     *   The calculation chain object specifies the order in which the cells in a workbook were last calculated
     * </p>
     * 
     * @return the <code>CalculationChain</code> object or <code>null</code> if not defined
     */
    public CalculationChain getCalculationChain(){
        return calcChain;
    }

}
