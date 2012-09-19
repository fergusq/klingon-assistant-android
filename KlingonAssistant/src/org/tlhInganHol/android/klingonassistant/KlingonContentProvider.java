/*
 * Copyright (C) 2012 De'vID jonpIn (David Yonge-Mallo)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.tlhInganHol.android.klingonassistant;

import android.app.SearchManager;
import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.provider.BaseColumns;
import android.util.Log;

//import java.util.Arrays;
import java.util.regex.*;
import java.util.ArrayList;

/**
 * Provides access to the dictionary database.
 */
public class KlingonContentProvider extends ContentProvider {
    // private static final String TAG = "KlingonContentProvider";

    public static String AUTHORITY = "org.tlhInganHol.android.klingonassistant.KlingonContentProvider";
    public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY);

    // MIME types used for searching entries or looking up a single definition
    public static final String ENTRIES_MIME_TYPE = ContentResolver.CURSOR_DIR_BASE_TYPE +
                                                  "/org.tlhInganHol.android.klingonassistant";
    public static final String DEFINITION_MIME_TYPE = ContentResolver.CURSOR_ITEM_BASE_TYPE +
                                                       "/org.tlhInganHol.android.klingonassistant";

    /**
     * The columns we'll include in our search suggestions.  There are others that could be used
     * to further customize the suggestions, see the docs in {@link SearchManager} for the details
     * on additional columns that are supported.
     */
    private static final String[] SUGGESTION_COLUMNS = {
            BaseColumns._ID,  // must include this column
            SearchManager.SUGGEST_COLUMN_TEXT_1,
            SearchManager.SUGGEST_COLUMN_TEXT_2,
            SearchManager.SUGGEST_COLUMN_INTENT_DATA,
            //SearchManager.SUGGEST_COLUMN_INTENT_DATA_ID,
            };

    // The actual Klingon Content Database.
    private KlingonContentDatabase mContentDatabase;

    // UriMatcher stuff
    private static final int SEARCH_ENTRIES = 0;
    private static final int GET_ENTRY = 1;
    private static final int SEARCH_SUGGEST = 2;
    private static final int REFRESH_SHORTCUT = 3;
    private static final int GET_ENTRY_BY_ID = 4;
    private static final UriMatcher sURIMatcher = buildUriMatcher();

    /**
     * Builds up a UriMatcher for search suggestion and shortcut refresh queries.
     */
    private static UriMatcher buildUriMatcher() {
        UriMatcher matcher =  new UriMatcher(UriMatcher.NO_MATCH);
        // to get definitions...
        matcher.addURI(AUTHORITY, "lookup", SEARCH_ENTRIES);
        matcher.addURI(AUTHORITY, "lookup/#", GET_ENTRY);
        // to get suggestions...
        matcher.addURI(AUTHORITY, SearchManager.SUGGEST_URI_PATH_QUERY, SEARCH_SUGGEST);
        matcher.addURI(AUTHORITY, SearchManager.SUGGEST_URI_PATH_QUERY + "/*", SEARCH_SUGGEST);

        // This is needed internally to get an entry by its id.
        matcher.addURI(AUTHORITY, "get_entry_by_id/#", GET_ENTRY_BY_ID);

        /* The following are unused in this implementation, but if we include
         * {@link SearchManager#SUGGEST_COLUMN_SHORTCUT_ID} as a column in our suggestions table, we
         * could expect to receive refresh queries when a shortcutted suggestion is displayed in
         * Quick Search Box, in which case, the following Uris would be provided and we
         * would return a cursor with a single item representing the refreshed suggestion data.
         */
        matcher.addURI(AUTHORITY, SearchManager.SUGGEST_URI_PATH_SHORTCUT, REFRESH_SHORTCUT);
        matcher.addURI(AUTHORITY, SearchManager.SUGGEST_URI_PATH_SHORTCUT + "/*", REFRESH_SHORTCUT);
        return matcher;
    }

    @Override
    public boolean onCreate() {
        mContentDatabase = new KlingonContentDatabase(getContext());
        return true;
    }

    /**
     * Handles all the database searches and suggestion queries from the Search Manager.
     * When requesting a specific entry, the uri alone is required.
     * When searching all of the database for matches, the selectionArgs argument must carry
     * the search query as the first element.
     * All other arguments are ignored.
     */
    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
                        String sortOrder) {

        // Use the UriMatcher to see what kind of query we have and format the db query accordingly
        switch (sURIMatcher.match(uri)) {
            case SEARCH_SUGGEST:
                // Uri has SUGGEST_URI_PATH_QUERY, i.e., "search_suggest_query".
                if (selectionArgs == null) {
                  throw new IllegalArgumentException(
                      "selectionArgs must be provided for the Uri: " + uri);
                }
                return getSuggestions(selectionArgs[0]);
            case SEARCH_ENTRIES:
                // Uri has "/lookup".
                if (selectionArgs == null) {
                  throw new IllegalArgumentException(
                      "selectionArgs must be provided for the Uri: " + uri);
                }
                return search(selectionArgs[0]);
            case GET_ENTRY:
                return getEntry(uri);
            case REFRESH_SHORTCUT:
                return refreshShortcut(uri);
            case GET_ENTRY_BY_ID:
                // This case was added to allow getting the entry by its id.
                String entryId = null;
                if (uri.getPathSegments().size() > 1) {
                    entryId = uri.getLastPathSegment();
                }
                // Log.d(TAG, "entryId = " + entryId);
                return getEntryById(entryId, projection);
            default:
                throw new IllegalArgumentException("Unknown Uri: " + uri);
        }
    }

    // (1) - This is the first way the database can be queried.
    // Called when uri has SUGGEST_URI_PATH_QUERY, i.e., "search_suggest_query".
    // This populates the dropdown list from the search box.
    private Cursor getSuggestions(String query) {
      // Log.d(TAG, "getSuggestions called with query: \"" + query + "\"");
      if (query.equals("")) {
          return null;
      }

      // First, get all the potentially relevant entries.  Include all columns of data.
      Cursor rawCursor = mContentDatabase.getEntryMatches(query);

      // Format to two columns for display.
      MatrixCursor formattedCursor = new MatrixCursor(SUGGESTION_COLUMNS);
      if( rawCursor.getCount() != 0 ) {
          rawCursor.moveToFirst();
          do {
              formattedCursor.addRow(formatEntryForSearchResults(rawCursor));
          } while( rawCursor.moveToNext() );
      }
      return formattedCursor;
    }

    private Object[] formatEntryForSearchResults(Cursor cursor) {
        // Format the search result for display here.
        Entry entry = new Entry(cursor);
        int entryId = entry.getId();
        String indent1 = entry.isIndented() ? "    " : "";
        String indent2 = entry.isIndented() ? "      " : "";
        String entryName = indent1 + entry.getFormattedEntryName(/* isHtml */ false);
        String formattedDefinition = indent2 + entry.getFormattedDefinition(/* isHtml */ false);
        // TODO(davinci): Format the "alt" results.

        // Search suggestions must have exactly four columns in exactly this format.
        return new Object[] {
                entryId,                            // _id
                entryName,                          // text1
                formattedDefinition,                // text2
                entryId,                            // intent_data (included when clicking on item)
        };
    }

    // (2) - This is the second way the database can be queried.
    // Called when uri has "/lookup".
    // Either we're following a link, or the user has pressed the "Go" button from search.
    private Cursor search(String query) {
      // Log.d(TAG, "search called with query: " + query);

      return mContentDatabase.getEntryMatches(query);
    }

    private Cursor getEntry(Uri uri) {
      // Log.d(TAG, "getEntry called with uri: " + uri.toString());
      String rowId = uri.getLastPathSegment();
      return mContentDatabase.getEntry(rowId, KlingonContentDatabase.ALL_KEYS);
    }

    private Cursor refreshShortcut(Uri uri) {
      /* This won't be called with the current implementation, but if we include
       * {@link SearchManager#SUGGEST_COLUMN_SHORTCUT_ID} as a column in our suggestions table, we
       * could expect to receive refresh queries when a shortcutted suggestion is displayed in
       * Quick Search Box. In which case, this method will query the table for the specific
       * entry, using the given item Uri and provide all the columns originally provided with the
       * suggestion query.
       */
      String rowId = uri.getLastPathSegment();
      /* String[] columns = new String[] {
          KlingonContentDatabase.KEY_ID,
          KlingonContentDatabase.KEY_ENTRY_NAME,
          KlingonContentDatabase.KEY_DEFINITION,
          // Add other keys here.
          SearchManager.SUGGEST_COLUMN_SHORTCUT_ID,
          SearchManager.SUGGEST_COLUMN_INTENT_DATA_ID}; */

      return mContentDatabase.getEntry(rowId, KlingonContentDatabase.ALL_KEYS);
    }

    /**
     * Retrieve a single entry by its _id.
     */
    private Cursor getEntryById(String entryId, String[] projection) {
        // Log.d(TAG, "getEntryById called with entryid: " + entryId);
        return mContentDatabase.getEntryById(entryId, projection);
    }

    /**
     * This method is required in order to query the supported types.
     * It's also useful in our own query() method to determine the type of Uri received.
     */
    @Override
    public String getType(Uri uri) {
        switch (sURIMatcher.match(uri)) {
            case SEARCH_ENTRIES:
                return ENTRIES_MIME_TYPE;
            case GET_ENTRY:
                return DEFINITION_MIME_TYPE;
            case SEARCH_SUGGEST:
                return SearchManager.SUGGEST_MIME_TYPE;
            case REFRESH_SHORTCUT:
                return SearchManager.SHORTCUT_MIME_TYPE;
            default:
                throw new IllegalArgumentException("Unknown URL " + uri);
        }
    }

    // Other required implementations...

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }

    // This class is for managing entries.
    public static class Entry {
        String TAG = "KlingonContentProvider.Entry";

        // Pattern for matching entry in text.
        public static Pattern ENTRY_PATTERN = Pattern.compile("\\{[A-Za-z0-9 '\\\":;,\\.\\-?!/()@=%&]+\\}");

        // The raw data for the entry.
        // private Uri mUri = null;
        private int mId = -1;
        private String mEntryName = "";
        private String mPartOfSpeech = "";
        private String mDefinition = "";
        private String mSynonyms = "";
        private String mAntonyms = "";
        private String mSeeAlso = "";
        private String mNotes = "";
        private String mHiddenNotes = "";
        private String mComponents = "";
        private String mExamples = "";
        private String mSearchTags = "";
        private String mSource = "";

        // Part of speech metadata.
        private enum BasePartOfSpeechEnum {
            NOUN,
            VERB,
            ADVERBIAL,
            CONJUNCTION,
            QUESTION,
            SENTENCE,
            EXCLAMATION,
            SOURCE,
            UNKNOWN
        }
        private String[] basePartOfSpeechAbbreviations = {
            "n", "v", "adv", "conj", "ques", "sen", "excl", "src", "???"
        };
        private BasePartOfSpeechEnum mBasePartOfSpeech = BasePartOfSpeechEnum.UNKNOWN;

        // Verb attributes.
        private enum VerbTransitivityType {
            TRANSITIVE, INTRANSITIVE, STATIVE, AMBITRANSITIVE, UNKNOWN
        }
        private VerbTransitivityType mTransitivity = VerbTransitivityType.UNKNOWN;

        // Noun attributes.
        private enum NounType {
            GENERAL, NUMBER, NAME, PRONOUN
        }
        private NounType mNounType = NounType.GENERAL;
        boolean mIsInherentPlural = false;
        boolean mIsSingularFormOfInherentPlural = false;
        boolean mIsPlural = false;

        // Sentence types.
        private enum SentenceType {
            PHRASE,
            EMPIRE_UNION_DAY,
            CURSE_WARFARE,
            IDIOM,
            NENTAY,
            PROVERB,
            MILITARY_CELEBRATION,
            REJECTION,
            REPLACEMENT_PROVERB,
            SECRECY_PROVERB,
            TOAST,
            LYRICS
        }
        private SentenceType mSentenceType = SentenceType.PHRASE;

        // Categories of words and phrases.
        boolean mIsAnimal = false;
        boolean mIsArchaic = false;
        boolean mIsBeingCapableOfLanguage = false;
        boolean mIsBodyPart = false;
        boolean mIsDerivative = false;
        boolean mIsRegional = false;
        boolean mIsFoodRelated = false;
        boolean mIsInvective = false;
        boolean mIsPlaceName = false;
        boolean mIsPrefix = false;
        boolean mIsSlang = false;
        boolean mIsSuffix = false;
        boolean mIsWeaponsRelated = false;

        // Additional metadata.
        boolean mIsAlternativeSpelling = false;
        boolean mIsFictionalEntity = false;
        boolean mIsHypothetical = false;
        boolean mIsExtendedCanon = false;
        boolean mDoNotLink = false;

        // For display purposes.
        boolean mIsIndented = false;

        // If there are multiple entries with identitical entry names,
        // they are distinguished with numbers.
        int mHomophoneNumber;

        // Sources can include a URL.
        String mSourceURL = "";

        /**
         * Constructor
         * @param query A query of the form "entryName:basepos:metadata".
         */
        public Entry(String query) {
            // Log.d(TAG, "Entry constructed from query: \"" + query + "\"");
            mEntryName = query;
            int colonLoc = query.indexOf(':');
            if (colonLoc != -1) {
                mEntryName = query.substring(0, colonLoc);
                mPartOfSpeech = query.substring(colonLoc + 1);
            }

            // Since this is a query, by default it doesn't match a specific number.
            mHomophoneNumber = -1;

            // Note: The homophone number may be overwritten by this function call.
            processMetadata();
        }

        /**
         * Constructor
         * @param cursor A cursor with position at the desired entry
         */
        public Entry(Cursor cursor) {
            mId = cursor.getInt(KlingonContentDatabase.COLUMN_ID);
            mEntryName = cursor.getString(KlingonContentDatabase.COLUMN_ENTRY_NAME);
            mPartOfSpeech = cursor.getString(KlingonContentDatabase.COLUMN_PART_OF_SPEECH);
            mDefinition = cursor.getString(KlingonContentDatabase.COLUMN_DEFINITION);
            mSynonyms = cursor.getString(KlingonContentDatabase.COLUMN_SYNONYMS);
            mAntonyms = cursor.getString(KlingonContentDatabase.COLUMN_ANTONYMS);
            mSeeAlso = cursor.getString(KlingonContentDatabase.COLUMN_SEE_ALSO);
            mNotes = cursor.getString(KlingonContentDatabase.COLUMN_NOTES);
            mHiddenNotes = cursor.getString(KlingonContentDatabase.COLUMN_HIDDEN_NOTES);
            mComponents = cursor.getString(KlingonContentDatabase.COLUMN_COMPONENTS);
            mExamples = cursor.getString(KlingonContentDatabase.COLUMN_EXAMPLES);
            mSearchTags = cursor.getString(KlingonContentDatabase.COLUMN_SEARCH_TAGS);
            mSource = cursor.getString(KlingonContentDatabase.COLUMN_SOURCE);

            // By default, an entry has the number 1 unless this is overwritten.
            mHomophoneNumber = 1;

            // Note: The homophone number may be overwritten by this function call.
            processMetadata();
        }

        // Helper method to process metadata.
        private void processMetadata() {

            // Process metadata from part of speech.
            String base = mPartOfSpeech;
            String[] attributes = {};
            int colonLoc = mPartOfSpeech.indexOf(':');
            if (colonLoc != -1) {
                base = mPartOfSpeech.substring(0, colonLoc);
                attributes = mPartOfSpeech.substring(colonLoc + 1).split(",");
            }

            // First, find the base part of speech.
            mBasePartOfSpeech = BasePartOfSpeechEnum.UNKNOWN;
            if (base.equals("")) {
                // Do nothing if base part of speech is empty.
                // Log.w(TAG, "{" + mEntryName + "} has empty part of speech.");
            } else {
                for (int i = 0; i < basePartOfSpeechAbbreviations.length; i++) {
                    if (base.equals(basePartOfSpeechAbbreviations[i])) {
                        mBasePartOfSpeech = BasePartOfSpeechEnum.values()[i];
                    }
                }
                if (mBasePartOfSpeech == BasePartOfSpeechEnum.UNKNOWN) {
                    // Log warning if part of speech could not be determined.
                    Log.w(TAG, "{" + mEntryName + "} has " + "unrecognised part of speech: \"" + mPartOfSpeech + "\"");
                }
            }

            // Now, get other attributes from the part of speech metadata.
            for (String attr : attributes) {

                // Ignore prefixes and suffixes.
                if (attr.equals("pref")) {
                    mIsIndented = true;
                    mIsPrefix = true;
                } else if (attr.equals("suff")) {
                    mIsIndented = true;
                    mIsSuffix = true;

                // Verb attributes.
                } else if (attr.equals("ambi")) {
                    mTransitivity = VerbTransitivityType.AMBITRANSITIVE;
                } else if (attr.equals("i")) {
                    mTransitivity = VerbTransitivityType.INTRANSITIVE;
                } else if (attr.equals("is")) {
                    mTransitivity = VerbTransitivityType.STATIVE;
                } else if (attr.equals("t")) {
                    mTransitivity = VerbTransitivityType.TRANSITIVE;

                // Noun attributes.
                } else if (attr.equals("name")) {
                    mNounType = NounType.NAME;
                } else if (attr.equals("num")) {
                    mNounType = NounType.NUMBER;
                } else if (attr.equals("pro")) {
                    mNounType = NounType.PRONOUN;
                } else if (attr.equals("inhpl")) {
                    mIsInherentPlural = true;
                } else if (attr.equals("inhps")) {
                    mIsSingularFormOfInherentPlural = true;
                } else if (attr.equals("plural")) {
                    mIsPlural = true;

                // Sentence attributes.
                } else if (attr.equals("eu")) {
                    mSentenceType = SentenceType.EMPIRE_UNION_DAY;
                } else if (attr.equals("mv")) {
                    mSentenceType = SentenceType.CURSE_WARFARE;
                } else if (attr.equals("idiom")) {
                    mSentenceType = SentenceType.IDIOM;
                } else if (attr.equals("nt")) {
                    mSentenceType = SentenceType.NENTAY;
                } else if (attr.equals("phr")) {
                    mSentenceType = SentenceType.PHRASE;
                } else if (attr.equals("prov")) {
                    mSentenceType = SentenceType.PROVERB;
                } else if (attr.equals("Ql")) {
                    mSentenceType = SentenceType.MILITARY_CELEBRATION;
                } else if (attr.equals("rej")) {
                    mSentenceType = SentenceType.REJECTION;
                } else if (attr.equals("rp")) {
                    mSentenceType = SentenceType.REPLACEMENT_PROVERB;
                } else if (attr.equals("sp")) {
                    mSentenceType = SentenceType.SECRECY_PROVERB;
                } else if (attr.equals("toast")) {
                    mSentenceType = SentenceType.TOAST;
                } else if (attr.equals("lyr")) {
                    mSentenceType = SentenceType.LYRICS;

                // Categories.
                } else if (attr.equals("anim")) {
                    mIsAnimal = true;
                } else if (attr.equals("archaic")) {
                    mIsArchaic = true;
                } else if (attr.equals("being")) {
                    mIsBeingCapableOfLanguage = true;
                } else if (attr.equals("body")) {
                    mIsBodyPart = true;
                } else if (attr.equals("deriv")) {
                    mIsDerivative = true;
                } else if (attr.equals("reg")) {
                    mIsRegional = true;
                } else if (attr.equals("food")) {
                    mIsFoodRelated = true;
                } else if (attr.equals("inv")) {
                    mIsInvective = true;
                } else if (attr.equals("place")) {
                    mIsPlaceName = true;
                } else if (attr.equals("slang")) {
                    mIsSlang = true;
                } else if (attr.equals("weap")) {
                    mIsWeaponsRelated = true;

                // Additional metadata.
                } else if (attr.equals("alt")) {
                    mIsAlternativeSpelling = true;
                } else if (attr.equals("fic")) {
                    mIsFictionalEntity = true;
                } else if (attr.equals("hyp")) {
                    mIsHypothetical = true;
                } else if (attr.equals("extcan")) {
                    mIsExtendedCanon = true;
                } else if (attr.equals("nolink")) {
                    mDoNotLink = true;

                // We have only a few homophonous entries.
                } else if (attr.equals("1")) {
                    mHomophoneNumber = 1;
                } else if (attr.equals("2")) {
                    mHomophoneNumber = 2;
                } else if (attr.equals("3")) {
                    mHomophoneNumber = 3;
                } else if (attr.equals("4")) {
                    mHomophoneNumber = 4;

                // If this is a source, the attribute is a URL.
                } else if (isSource()) {
                    mSourceURL = attr;

                // No match to attributes.
                } else {
                    // Log error if part of speech could not be determined.
                    Log.e(TAG, "Unrecognised attribute: \"" + attr + "\"");
                }

            }
        }

        // Get the _id of the entry.
        public int getId() {
            return mId;
        }

        private String maybeItalics(String s, boolean isHtml) {
            if (isHtml) {
                return "<i>" + s + "</i>";
            }
            return s;
        }

        // Get the name of the entry, as an HTML string.
        public String getFormattedEntryName(boolean isHtml) {
            // Note that an entry may have more than one of the archaic,
            // regional, or slang attributes.
            String attr = "";
            if (mIsArchaic) {
                attr = maybeItalics("archaic", isHtml);
            } 
            if (mIsRegional) {
                if (!attr.equals("")) {
                    attr += ", ";
                }
                attr = maybeItalics("regional", isHtml);
            }
            if (mIsSlang) {
                if (!attr.equals("")) {
                    attr += ", ";
                }
                attr += maybeItalics("slang", isHtml);
            }
            if (!attr.equals("")) {
                if (isHtml) {
                    // Should also set color to android:textColorSecondary.
                    attr = " <small>(" + attr + ")</small>";
                } else {
                    attr = " (" + attr + ")";
                }
            }

            // Mark hypothetical and extended canon entries with a "?".
            String formattedEntryName = mEntryName + attr;
            if (mIsHypothetical || mIsExtendedCanon) {
                if (isHtml) {
                    formattedEntryName = "<sup><small>?</small></sup>" + formattedEntryName;
                } else {
                    formattedEntryName = "?" + formattedEntryName;
                }
            }

            // Return name plus possible attribute.
            return formattedEntryName;
        }

        private String getSpecificPartOfSpeech() {
            String pos = basePartOfSpeechAbbreviations[mBasePartOfSpeech.ordinal()];
            if (mBasePartOfSpeech == BasePartOfSpeechEnum.NOUN) {
                if (mNounType == NounType.NUMBER) {
                    pos = "num";
                } else if (mNounType == NounType.NAME) {
                    pos = "name";
                } else if (mNounType == NounType.PRONOUN) {
                    pos = "pro";
                }
            }
            return pos;
        }

        public String getFormattedPartOfSpeech(boolean isHtml) {
            // Return abbreviation for part of speech, but suppress for sentences and names.
            String pos = "";
            if (isAlternativeSpelling()) {
                pos = "See: ";
            } else if (
                mBasePartOfSpeech == BasePartOfSpeechEnum.SENTENCE ||
                mBasePartOfSpeech == BasePartOfSpeechEnum.NOUN && mNounType == NounType.NAME) {
              // Ignore part of speech for names and sentences.
              pos = "";
            } else {
              pos = getSpecificPartOfSpeech();
              if (isHtml) {
                pos = "<i>" + pos + ".</i> ";
              } else {
                pos = pos + ". ";
              }
            }
            return pos;
        }

        // Get the definition, including the part of speech.
        public String getFormattedDefinition(boolean isHtml) {
            String pos = getFormattedPartOfSpeech(isHtml);

            // Replace brackets in definition with bold.
            String definition = mDefinition;
            Matcher matcher = ENTRY_PATTERN.matcher(definition);
            while (matcher.find()) {
                // Strip brackets.
                String query = definition.substring(matcher.start() + 1, matcher.end() - 1);
                KlingonContentProvider.Entry linkedEntry = new KlingonContentProvider.Entry(query);
                String replacement;
                if (isHtml) {
                    // Bold a Klingon word if there is one.
                    replacement = "<b>" + linkedEntry.getEntryName() + "</b>";
                } else {
                    // Just replace it with plain text.
                    replacement = linkedEntry.getEntryName();
                }
                definition = definition.substring(0, matcher.start()) + replacement + definition.substring(matcher.end());

                // Repeat.
                matcher = ENTRY_PATTERN.matcher(definition);
            }

            // Return the definition, preceded by the part of speech.
            return pos + definition;
        }

        public String getEntryName() {
            return mEntryName;
        }

        // Return the part of speech in brackets, but only for some cases.
        public String getBracketedPartOfSpeech(boolean isHtml) {
            // Return abbreviation for part of speech, but suppress for sentences and exclamations.
            if (mBasePartOfSpeech == BasePartOfSpeechEnum.SENTENCE ||
                mBasePartOfSpeech == BasePartOfSpeechEnum.EXCLAMATION ||
                mBasePartOfSpeech == BasePartOfSpeechEnum.SOURCE ||
                mBasePartOfSpeech == BasePartOfSpeechEnum.UNKNOWN ||
                (mBasePartOfSpeech == BasePartOfSpeechEnum.NOUN && mNounType == NounType.NAME)) {
              return "";
            }
            String pos = getSpecificPartOfSpeech();

            if (isHtml) {
                return " <small>(<i>" + pos + "</i>)</small>";
            } else {
                return " (" + pos + ")";
            }
        }

        public String getPartOfSpeech() {
            return mPartOfSpeech;
        }

        public boolean basePartOfSpeechIsUnknown() {
            return mBasePartOfSpeech == BasePartOfSpeechEnum.UNKNOWN;
        }

        public BasePartOfSpeechEnum getBasePartOfSpeech() {
            return mBasePartOfSpeech;
        }

        public String getDefinition() {
            if (mBasePartOfSpeech == BasePartOfSpeechEnum.NOUN && mNounType == NounType.NAME) {
                return mDefinition + " (name)";
            }
            return mDefinition;
        }

        public String getSynonyms() {
            return mSynonyms;
        }

        public String getAntonyms() {
            return mAntonyms;
        }
        public String getSeeAlso() {
            return mSeeAlso;
        }

        public String getNotes() {
            return mNotes;
        }

        public String getHiddenNotes() {
            return mHiddenNotes;
        }

        public String getComponents() {
            return mComponents;
        }

        public String getExamples() {
            return mExamples;
        }

        public String getSearchTags() {
            return mSearchTags;
        }

        public String getSource() {
            return mSource;
        }

        public int getHomophoneNumber() {
            return mHomophoneNumber;
        }

        public boolean isInherentPlural() {
            return mIsInherentPlural;
        }

        public boolean isSingularFormOfInherentPlural() {
            return mIsSingularFormOfInherentPlural;
        }

        public boolean isPlural() {
            // This noun is already plural (e.g., the entry already has plural suffixes).
            // This is different from an inherent plural, which acts like a singular object
            // for the purposes of verb agreement.
            return mIsPlural;
        }

        public boolean isArchaic() {
            return mIsArchaic;
        }

        public boolean isBeingCapableOfLanguage() {
            return mIsBeingCapableOfLanguage;
        }

        public boolean isBodyPart() {
            return mIsBodyPart;
        }

        public boolean isDerivative() {
            return mIsDerivative;
        }

        public boolean isRegional() {
            return mIsRegional;
        }

        public boolean isFoodRelated() {
            return mIsFoodRelated;
        }

        public boolean isPlaceName() {
            return mIsPlaceName;
        }
        public boolean isInvective() {
            return mIsInvective;
        }

        public boolean isSlang() {
            return mIsSlang;
        }

        public boolean isWeaponsRelated() {
            return mIsWeaponsRelated;
        }

        public boolean isAlternativeSpelling() {
            return mIsAlternativeSpelling;
        }

        public boolean isFictionalEntity() {
            return mIsFictionalEntity;
        }

        public boolean isHypothetical() {
            return mIsHypothetical;
        }

        public boolean isExtendedCanon() {
            return mIsExtendedCanon;
        }

        public boolean isSource() {
            return mBasePartOfSpeech == BasePartOfSpeechEnum.SOURCE;
        }

        public String getSourceURL() {
            return mSourceURL;
        }

        public boolean doNotLink() {
            return mDoNotLink;
        }

        public boolean isIndented() {
            return mIsIndented;
        }

        public boolean isPronoun() {
            return mBasePartOfSpeech == BasePartOfSpeechEnum.NOUN &&
                   mNounType == NounType.PRONOUN;
        }

        public boolean isName() {
            return mBasePartOfSpeech == BasePartOfSpeechEnum.NOUN &&
                   mNounType == NounType.NAME;
        }

        public boolean isNumber() {
            return mBasePartOfSpeech == BasePartOfSpeechEnum.NOUN &&
                   mNounType == NounType.NUMBER;
        }

        public boolean isSentence() {
            return mBasePartOfSpeech == BasePartOfSpeechEnum.SENTENCE;
        }

        public boolean isVerb() {
            return mBasePartOfSpeech == BasePartOfSpeechEnum.VERB;
        }

        public boolean isPrefix() {
            return mIsPrefix;
        }

        public boolean isSuffix() {
            return mIsSuffix;
        }

        public String getTransitivity() {
            switch (mTransitivity) {
                case AMBITRANSITIVE:
                    return "both transitive and intransitive";

                case INTRANSITIVE:
                    return "intransitive";

                case STATIVE:
                    return "intransitive (state or quality)";

                case TRANSITIVE:
                    return "transitive";

                default:
                    return "unknown";
            }

        }

        // Called on a query entry, determines if the query is satisfied by the candidate entry.
        public boolean isSatisfiedBy(Entry candidate) {
            // Log.d(TAG, "isSatisfiedBy candidate: " + candidate.getEntryName());

            // Determine whether entry name matches exactly.
            boolean isExactMatchForEntryName = mEntryName.equals(candidate.getEntryName());

            // If the part of speech is unknown, be much less strict, because
            // the query was typed from the search box.
            if (!basePartOfSpeechIsUnknown()) {
                // Base part of speech is known, so match exact entry name as
                // well as base part of speech.
                if (!isExactMatchForEntryName) {
                    return false;
                }
                // If we're looking for a verb, a pronoun will satisfy the requirement.
                // Otherwise, the parts of speech must match.
                if ((mBasePartOfSpeech != BasePartOfSpeechEnum.VERB || !candidate.isPronoun()) &&
                    (mBasePartOfSpeech != candidate.getBasePartOfSpeech())) {
                    // Log.d(TAG, "isExactMatchForEntryName: " + isExactMatchForEntryName);
                    // Log.d(TAG, "mBasePartOfSpeech: " + mBasePartOfSpeech);
                    // Log.d(TAG, "candidate.getBasePartOfSpeech: " + candidate.getBasePartOfSpeech());
                    return false;
                }
            }
            // Log.d(TAG, "Exact name match for entry name: " + candidate.getEntryName());
            // Log.d(TAG, "Part of speech satisfied for: " + candidate.getEntryName());

            // If the homophone number is given, it must match.
            if (mHomophoneNumber != -1 &&
                mHomophoneNumber != candidate.getHomophoneNumber()) {
                return false;
            }

            // If search for an attribute, candidate must have it.
            if (mIsSlang && !candidate.isSlang()) {
                return false;
            }
            if (mIsRegional && !candidate.isRegional()) {
                return false;
            }
            if (mIsArchaic && !candidate.isArchaic()) {
                return false;
            }
            if (isName() && !candidate.isName()) {
                return false;
            }
            if (isNumber() && !candidate.isNumber()) {
                return false;
            }

            // Treat fictional differently?
            // if (mIsFictional && !candidate.isFictional()) {
            //     return false;
            // }

            // TODO: Test a bunch of other things here.
            // Log.d(TAG, "Candidate passed: " + candidate.getEntryName());
            return true;
        }
    }

    // This class is for complex Klingon words.  A complex word is a noun or verb with affixes.
    public static class ComplexWord {
        String TAG = "KlingonContentProvider.ComplexWord";

        // The stem of this complex word.
        // private Entry stemEntry = null;

        // The noun suffixes.
        static String[] nounType1String = {
            "", "'a'", "Hom", "oy"
        };
        static String[] nounType2String = {
            "", "pu'", "Du'", "mey"
        };
        static String[] nounType3String = {
            "", "qoq", "Hey", "na'"
        };
        static String[] nounType4String = {
            "", "wIj", "wI'", "maj", "ma'", "lIj", "lI'", "raj", "ra'", "Daj", "chaj", "vam", "vetlh"
        };
        static String[] nounType5String = {
            "", "Daq", "vo'", "mo'", "vaD", "'e'"
        };
        static String[][] nounSuffixesStrings = {
            nounType1String,
            nounType2String,
            nounType3String,
            nounType4String,
            nounType5String
        };
        int mNounSuffixes[] = new int[nounSuffixesStrings.length];

        // The verb prefixes.
        static String[] verbPrefixString = {
            "", "bI", "bo", "che", "cho", "Da", "DI", "Du",
            "gho", "HI", "jI", "ju", "lI", "lu", "ma", "mu",
            "nI", "nu", "pe", "pI", "qa", "re",
            "Sa", "Su", "tI", "tu", "vI", "wI", "yI"
        };
        static String[] verbTypeRUndoString = {
            // {-Ha'} always occurs immediately after the verb.
            "", "Ha'"
        };
        static String[] verbType1String = {
            "", "'egh", "chuq"
        };
        static String[] verbType2String = {
            "", "nIS", "qang", "rup", "beH", "vIp"
        };
        static String[] verbType3String = {
            "", "choH", "qa'"
        };
        static String[] verbType4String = {
            "", "moH"
        };
        static String[] verbType5String = {
            "", "lu'", "laH"
        };
        static String[] verbType6String = {
            "", "chu'", "bej", "ba'", "law'"
        };
        static String[] verbType7String = {
            "", "pu'", "ta'", "taH", "lI'"
        };
        static String[] verbType8String = {
            "", "neS"
        };
        static String[] verbTypeRRefusal = {
            // {-Qo'} always occurs last, unless followed by a type 9 suffix.
            "", "Qo'"
        };
        static String[] verbType9String = {
            "", "DI'", "chugh", "pa'", "vIS", "mo'", "bogh", "meH", "'a'", "jaj", "wI'", "ghach"
        };
        static String[][] verbSuffixesStrings = {
            verbTypeRUndoString,
            verbType1String,
            verbType2String,
            verbType3String,
            verbType4String,
            verbType5String,
            verbType6String,
            verbType7String,
            verbType8String,
            verbTypeRRefusal,
            verbType9String
        };
        int mVerbPrefix;
        int mVerbSuffixes[] = new int[verbSuffixesStrings.length];

        // The locations of the true rovers.  The value indicates the suffix type they appear after,
        // so 0 means they are attached directly to the verb (before any type 1 suffix).
        int mVerbTypeRNegation;
        int mVerbTypeREmphatic;

        // True if {-be'} appears before {-qu'} in a verb.
        boolean roverOrderNegationBeforeEmphatic;

        // Internal information related to processing the complex word candidate.
        String mUnparsedPart;
        int mSuffixLevel;
        boolean mIsNoun;

        /**
         * Constructor
         * @param candidate A potential candidate for a complex word.
         * @param isNoun Set to true if noun, false if verb.
         */
        public ComplexWord(String candidate, boolean isNoun) {
            mUnparsedPart = candidate;
            mIsNoun = isNoun;

            if (mIsNoun) {
                // Five types of noun suffixes.
                mSuffixLevel = nounSuffixesStrings.length;
            } else {
                // Nine types of verb suffixes.
                mSuffixLevel = verbSuffixesStrings.length;
            }

            for (int i = 0; i < mNounSuffixes.length; i++) {
                mNounSuffixes[i] = 0;
            }

            mVerbPrefix = 0;
            for (int i = 0; i < mVerbSuffixes.length; i++) {
                mVerbSuffixes[i] = 0;
            }

            // Rovers.
            mVerbTypeRNegation = -1;
            mVerbTypeREmphatic = -1;
            roverOrderNegationBeforeEmphatic = false;
        }

        /**
         * Copy constructor
         * @param unparsedPart The unparsedPart of this complex word.
         * @param complexWordToCopy
         */
        public ComplexWord(String unparsedPart, ComplexWord complexWordToCopy) {
            mUnparsedPart = unparsedPart;
            mIsNoun = complexWordToCopy.mIsNoun;
            mSuffixLevel = complexWordToCopy.mSuffixLevel;
            mVerbPrefix = complexWordToCopy.mVerbPrefix;
            for (int i = 0; i < mNounSuffixes.length; i++) {
                mNounSuffixes[i] = complexWordToCopy.mNounSuffixes[i];
            }
            for (int j = 0; j < mVerbSuffixes.length; j++) {
                mVerbSuffixes[j] = complexWordToCopy.mVerbSuffixes[j];
            }
            mVerbTypeRNegation = complexWordToCopy.mVerbTypeRNegation;
            mVerbTypeREmphatic = complexWordToCopy.mVerbTypeREmphatic;
            roverOrderNegationBeforeEmphatic = complexWordToCopy.roverOrderNegationBeforeEmphatic;
        }

        public ComplexWord stripPrefix() {
            if (mIsNoun) {
                return null;
            }

            // Count from 1, since index 0 corresponds to no prefix.
            for (int i = 1; i < verbPrefixString.length; i++) {
                // Log.d(TAG, "checking prefix: " + verbPrefixString[i]);
                if (mUnparsedPart.startsWith(verbPrefixString[i])) {
                    String partWithPrefixRemoved = mUnparsedPart.substring(verbPrefixString[i].length());
                    // Log.d(TAG, "found prefix: " + verbPrefixString[i] + ", remainder: " + partWithPrefixRemoved);
                    if (!partWithPrefixRemoved.equals("")) {
                        ComplexWord anotherComplexWord = new ComplexWord(partWithPrefixRemoved, this);
                        anotherComplexWord.mVerbPrefix = i;
                        return anotherComplexWord;
                    }

                }
            }
            return null;
        }

        // Attempt to strip off the rovers.
        private void stripRovers() {
            // We must preserve the relative order of the two true rovers.
            if (mUnparsedPart.endsWith("be'qu'")) {
                mVerbTypeRNegation = mSuffixLevel;
                mVerbTypeREmphatic = mSuffixLevel;
                roverOrderNegationBeforeEmphatic = true;
                mUnparsedPart = mUnparsedPart.substring(0, mUnparsedPart.length() - 6);
            } else if (mUnparsedPart.endsWith("qu'be'")) {
                mVerbTypeRNegation = mSuffixLevel;
                mVerbTypeREmphatic = mSuffixLevel;
                roverOrderNegationBeforeEmphatic = false;
                mUnparsedPart = mUnparsedPart.substring(0, mUnparsedPart.length() - 6);
            } else if (mUnparsedPart.endsWith("be'")) {
                mVerbTypeRNegation = mSuffixLevel;
                mUnparsedPart = mUnparsedPart.substring(0, mUnparsedPart.length() - 3);
            } else if (mUnparsedPart.endsWith("qu'")) {
                mVerbTypeREmphatic = mSuffixLevel;
                mUnparsedPart = mUnparsedPart.substring(0, mUnparsedPart.length() - 3);
            }
        }

        // Attempt to strip off one level of suffix from self, if this results in a branch return the branch as a new complex word.
        // At the end of this call, this complex word will have have decreased one suffix level.
        public ComplexWord stripSuffix() {
            if (mSuffixLevel == 0) {
                // This should never be reached.
                return null;
            }
            // The types are 1-indexed, but the array is 0-index, so decrement it here.
            mSuffixLevel--;
            String[] suffixes;
            if (mIsNoun) {
                suffixes = nounSuffixesStrings[mSuffixLevel];
            } else {
                suffixes = verbSuffixesStrings[mSuffixLevel];
                stripRovers();
            }

            // Count from 1, since index 0 corresponds to no suffix of this type.
            for (int i = 1; i < suffixes.length; i++) {
                // Log.d(TAG, "checking suffix: " + suffixes[i]);
                if (mUnparsedPart.endsWith(suffixes[i])) {
                    // Found a suffix of the current type, strip it.
                    String partWithSuffixRemoved = mUnparsedPart.substring(0, mUnparsedPart.length() - suffixes[i].length());
                    // Log.d(TAG, "found suffix: " + suffixes[i] + ", remainder: " + partWithSuffixRemoved);
                    if (!partWithSuffixRemoved.equals("")) {
                        ComplexWord anotherComplexWord = new ComplexWord(partWithSuffixRemoved, this);
                        // mSuffixLevel already decremented above.
                        anotherComplexWord.mSuffixLevel = mSuffixLevel;
                        if (mIsNoun) {
                            anotherComplexWord.mNounSuffixes[anotherComplexWord.mSuffixLevel] = i;
                        } else {
                            anotherComplexWord.mVerbSuffixes[anotherComplexWord.mSuffixLevel] = i;
                        }
                        return anotherComplexWord;
                    }
                }
            }
            return null;
        }

        private boolean hasNoMoreSuffixes() {
            return mSuffixLevel == 0;
        }

        // Returns true if this is not a complex word after all.
        public boolean isBareWord() {
            if (mVerbPrefix != 0) {
                // A verb prefix was found.
                return false;
            }
            if (mVerbTypeRNegation != -1 ||
                mVerbTypeREmphatic != -1) {
                // A rover was found.
                return false;
            }
            for (int i = 0; i < mNounSuffixes.length; i++) {
                if (mNounSuffixes[i] != 0) {
                    // A noun suffix was found.
                    return false;
                }
            }
            for (int j = 0; j < mVerbSuffixes.length; j++) {
                if (mVerbSuffixes[j] != 0) {
                    // A verb suffix was found.
                    return false;
                }
            }
            // None found.
            return true;
        }

        private boolean noNounSuffixesFound() {
            for (int i = 0; i < mNounSuffixes.length; i++) {
                if (mNounSuffixes[i] != 0) {
                    // A noun suffix was found.
                    return false;
                }
            }
            // None found.
            return true;
        }

        public String toString() {
            String s = mUnparsedPart;
            if (mIsNoun) {
                s += " (n)";
                for (int i = 0; i < mNounSuffixes.length; i++ ) {
                    s += " " + mNounSuffixes[i];
                }
            } else {
                s += " (v) ";
                for (int i = 0; i < mVerbSuffixes.length; i++ ) {
                    s += " " + mVerbSuffixes[i];
                }
            }
            return s;
        }

        public String filter() {
            if (isBareWord()) {
                // If there are no prefixes or suffixes, match any part of speech.
                return mUnparsedPart;
            }
            return mUnparsedPart + ":" + (mIsNoun ? "n" : "v");
        }

        public String stem() {
            return mUnparsedPart;
        }

        // Get the entry name for the verb prefix.
        public String getVerbPrefix() {
            return verbPrefixString[mVerbPrefix] + (mVerbPrefix == 0 ? "" : "-");
        }

        // Get the entry names for the verb suffixes.
        public String[] getVerbSuffixes() {
            String[] suffixes = new String[mVerbSuffixes.length];
            for (int i = 0; i < mVerbSuffixes.length; i++) {
                suffixes[i] = (mVerbSuffixes[i] == 0 ? "" : "-") + verbSuffixesStrings[i][mVerbSuffixes[i]];
            }
            return suffixes;
        }

        // Get the entry names for the noun suffixes.
        public String[] getNounSuffixes() {
            String[] suffixes = new String[mNounSuffixes.length];
            for (int i = 0; i < mNounSuffixes.length; i++) {
                suffixes[i] = (mNounSuffixes[i] == 0 ? "" : "-") + nounSuffixesStrings[i][mNounSuffixes[i]];
            }
            return suffixes;
        }

        // Get the rovers at a given suffix level.
        public String[] getRovers(int suffixLevel) {
            final String[] negationThenEmphatic = { "-be'", "-qu'" };
            final String[] emphaticThenNegation = { "-qu'", "-be'" };
            final String[] negationOnly = { "-be'" };
            final String[] emphaticOnly = { "-qu'" };
            final String[] none = {};
            if (mVerbTypeRNegation == suffixLevel && mVerbTypeREmphatic == suffixLevel) {
                return (roverOrderNegationBeforeEmphatic ? negationThenEmphatic : emphaticThenNegation);
            } else if (mVerbTypeRNegation == suffixLevel) {
                return negationOnly;
            } else if (mVerbTypeREmphatic == suffixLevel) {
                return emphaticOnly;
            }
            return none;
        }

        // For display.
        public String getVerbPrefixString() {
            return verbPrefixString[mVerbPrefix] + (mVerbPrefix == 0 ? "" : "- + ");
        }

        // For display.
        public String getSuffixesString() {
            String suffixesString = "";
            // Verb suffixes have to go first, since some can convert a verb to a noun.
            for (int i = 0; i < mVerbSuffixes.length; i++) {
                String[] suffixes = verbSuffixesStrings[i];
                if (mVerbTypeRNegation == i && mVerbTypeREmphatic == i) {
                    if (roverOrderNegationBeforeEmphatic) {
                        suffixesString += " + -be' + qu'";
                    } else {
                        suffixesString += " + -qu' + be'";
                    }
                } else if (mVerbTypeRNegation == i) {
                    suffixesString += " + -be'";
                } else if (mVerbTypeREmphatic == i) {
                    suffixesString += " + -qu'";
                }
                if (mVerbSuffixes[i] != 0) {
                    suffixesString += " + -";
                    suffixesString += suffixes[mVerbSuffixes[i]];
                }
            }
            // Noun suffixes.
            for (int j = 0; j < mNounSuffixes.length; j++) {
                String[] suffixes = nounSuffixesStrings[j];
                if (mNounSuffixes[j] != 0) {
                    suffixesString += " + -";
                    suffixesString += suffixes[mNounSuffixes[j]];
                }
            }
            return suffixesString;
        }

        public ComplexWord getVerbRootIfNoun() {
            if (!mIsNoun || !hasNoMoreSuffixes()) {
                // Should never be reached.
                return null;
            }
            // Log.d(TAG, "getVerbRootIfNoun on: " + mUnparsedPart);

            // If the unparsed part ends in a suffix that nominalises a verb ({-wI'}, {-ghach}), analysize it further.
            // Do this only if there were noun suffixes, since the bare noun will be analysed as a verb anyway.
            if (!noNounSuffixesFound() && (mUnparsedPart.endsWith("ghach") || mUnparsedPart.endsWith("wI'"))) {
                // Log.d(TAG, "Creating verb from: " + mUnparsedPart);
                ComplexWord complexVerb = new ComplexWord(mUnparsedPart, this);
                complexVerb.mIsNoun = false;
                complexVerb.mSuffixLevel = complexVerb.mVerbSuffixes.length;
                return complexVerb;
            }
            return null;
        }


        private void addSelf(ArrayList<ComplexWord> complexWordsList) {
            if (!hasNoMoreSuffixes()) {
                // This point should never be reached.
                Log.e(TAG, "addSelf called on " + mUnparsedPart + " with suffix level " + mSuffixLevel + ".");
                return;
            }
            // if (isBareWord()) {
                // This is not a complex word, do nothing.
                // TODO: Fix this bug.
                // Problem: "batlh bIHeghjaj" should display "batlh".
                // But: "tuQHa'" should display "tuQHa'" once.
                // return;
            // }
            // Log.d(TAG, "Found: " + this.toString());
            complexWordsList.add(this);
        }

    }

    // Attempt to parse this complex word, and if successful, add it to the given set.
    public static void parse(String candidate, boolean isNoun, ArrayList<ComplexWord> complexWordsList) {
        ComplexWord complexWord = new ComplexWord(candidate, isNoun);
        // Log.d(TAG, "parsing = " + candidate + " (" + (isNoun ? "n" : "v") + ")");
        if (!isNoun) {
            // Check prefix.
            ComplexWord strippedPrefixComplexWord = complexWord.stripPrefix();
            if (strippedPrefixComplexWord != null) {
                // Branch off a word with the prefix stripped.
                stripSuffix(strippedPrefixComplexWord, complexWordsList);
            }
        }
        // Check suffixes.
        stripSuffix(complexWord, complexWordsList);
    }

    // Helper method to strip a level of suffix from a word.
    private static void stripSuffix(ComplexWord complexWord, ArrayList<ComplexWord> complexWordsList) {
        if (complexWord.hasNoMoreSuffixes()) {
            // Log.d(TAG, "adding self: " + complexWord.mUnparsedPart);
            complexWord.addSelf(complexWordsList);

            // Attempt to get the verb root of this word if it's a noun.
            complexWord = complexWord.getVerbRootIfNoun();
            if (complexWord == null) {
                return;
            }
        }
        // Log.d(TAG, "stripSuffix " + (complexWord.mIsNoun ? "noun" : "verb") + " type " +
        //     complexWord.mSuffixLevel + " on \"" + complexWord.mUnparsedPart + "\"");

        // Attempt to strip one level of suffix.
        ComplexWord strippedSuffixComplexWord = complexWord.stripSuffix();
        if (strippedSuffixComplexWord != null) {
            // A suffix of the current type was found, branch using it as a new candidate.
            stripSuffix(strippedSuffixComplexWord, complexWordsList);
        }
        // Tail recurse to the next level of suffix.
        stripSuffix(complexWord, complexWordsList);
    }
}