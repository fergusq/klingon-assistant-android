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

import java.util.regex.Matcher;

import android.app.Activity;
import android.app.SearchManager;
import android.content.ComponentName;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.text.Html;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.method.LinkMovementMethod;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.ClickableSpan;
import android.text.style.StyleSpan;
import android.text.style.SuperscriptSpan;
import android.text.style.TypefaceSpan;
import android.text.style.URLSpan;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;

/**
 * Displays an entry and its definition.
 */
public class EntryActivity extends Activity {
    // private static final String TAG = "EntryActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.entry);

        // BACKPORT: No Action Bar.

        TextView entryTitle = (TextView) findViewById(R.id.entry);
        TextView entryText = (TextView) findViewById(R.id.definition);

        // TODO: Save and restore bundle state to preserve links.

        Uri uri = getIntent().getData();
        // Log.d(TAG, "EntryActivity - uri: " + uri.toString());

        // Retrieve the entry's data.
        // Note: managedQuery is deprecated since API 11.
        Cursor cursor = managedQuery(uri, KlingonContentDatabase.ALL_KEYS, null, null, null);
        KlingonContentProvider.Entry entry = new KlingonContentProvider.Entry(cursor);

        // Handle alternative spellings here.
        if (entry.isAlternativeSpelling()) {
            // TODO: Immediate redirect to query in entry.getDefinition();
        }

        // Set the entry's name (along with info like "slang", formatted in HTML).
        String entryName = entry.getFormattedEntryName(/* isHtml */ true);
        entryTitle.setText(Html.fromHtml(entryName));

        // Create the expanded definition.
        String pos = entry.getFormattedPartOfSpeech(/* isHtml */ false);
        String expandedDefinition = pos + entry.getDefinition();

        // Show the basic notes.
        String notes = entry.getNotes();
        if (!notes.equals("")) {
            expandedDefinition += "\n\n" + notes;
        }

        // If this entry is hypothetical or extended canon, display warnings.
        if (entry.isHypothetical() || entry.isExtendedCanon()) {
            expandedDefinition += "\n\n";
            if (entry.isHypothetical()) {
                expandedDefinition += "Warning: This entry is hypothetical. Use with caution!";
            }
            if (entry.isExtendedCanon()) {
                expandedDefinition += "Warning: This entry is from extended canon. Use with caution!";
            }
        }

        // Show synonyms, antonyms, and related entries.
        String synonyms = entry.getSynonyms();
        String antonyms = entry.getAntonyms();
        String seeAlso = entry.getSeeAlso();
        if (!synonyms.equals(""))  {
            expandedDefinition += "\n\nSynonyms: " + synonyms;
        }
        if (!antonyms.equals(""))  {
            expandedDefinition += "\n\nAntonyms: " + antonyms;
        }
        if (!seeAlso.equals(""))  {
            expandedDefinition += "\n\nSee also: " + seeAlso;
        }

        // Display components if that field is not empty.
        String components = entry.getComponents();
        if (!components.equals(""))  {
            // Treat the components column of inherent plurals and their
            // singulars differently than for other entries.
            if (entry.isInherentPlural()) {
                expandedDefinition += "\n\nThis word is inherently plural.  " +
                  "Its singular form is " + components + ".";
            } else if (entry.isSingularFormOfInherentPlural()) {
                expandedDefinition += "\n\nThis word has an inherently " +
                  "plural form in " + components + ".";
            } else {
                // This is just a regular list of components.
                expandedDefinition += "\n\nComponents: " + components;
            }
        }

        // Display plural information.
        if (!entry.isPlural() && !entry.isInherentPlural() && !entry.isPlural()) {
            if (entry.isBeingCapableOfLanguage()) {
                expandedDefinition += "\n\nThis word refers to a being capable of language.  " +
                    "It takes the plural suffix {-pu':n:suff}.";
            } else if (entry.isBodyPart()) {
                expandedDefinition += "\n\nThis word refers to a body part.  " +
                    "It takes the plural suffix {-Du':n:suff}.";
            }
        }

        // If the entry is a sentence, make a link to analyse its components.
        if (entry.isSentence() || entry.isDerivative()) {
            // TODO: If components is not empty, use that information.
            expandedDefinition += "\n\nAnalyze: {" + entry.getEntryName() + "}";
        }

        // Show the examples.
        String examples = entry.getExamples();
        if (!examples.equals(""))  {
            expandedDefinition += "\n\nExamples: " + examples;
        }

        // Show the source.
        String source = entry.getSource();
        if (!source.equals(""))  {
            expandedDefinition += "\n\nSource(s): " + source;
        }

        // If this is a verb (but not a prefix or suffix), show the transitivity information.
        String transitivity = entry.getTransitivity();
        int transitivityStart = -1;
        String transitivityHeader = "\n\nTransitivity (best guess): "; 
        if (entry.isVerb() && !entry.isPrefix() && !entry.isSuffix()) {
            transitivityStart = expandedDefinition.length();
            expandedDefinition += transitivityHeader + transitivity;
        }

        // Show the hidden notes.
        String hiddenNotes = entry.getHiddenNotes();
        // TODO: Provide preference for hiding them.
        int hiddenNotesStart = -1;
        String hiddenNotesHeader = "\n\nAdditional information: ";
        if (!hiddenNotes.equals(""))  {
            hiddenNotesStart = expandedDefinition.length();
            expandedDefinition += hiddenNotesHeader + hiddenNotes;
        }

        // Format the expanded definition, including linkifying the links to other entries.
        Matcher m = KlingonContentProvider.Entry.ENTRY_PATTERN.matcher(expandedDefinition);
        SpannableStringBuilder ssb = new SpannableStringBuilder(expandedDefinition);
        int intermediateFlags = Spannable.SPAN_EXCLUSIVE_EXCLUSIVE | Spannable.SPAN_INTERMEDIATE;
        int finalFlags = Spannable.SPAN_EXCLUSIVE_EXCLUSIVE;
        if (!pos.equals("")) {
            // Italicise the part of speech.
            ssb.setSpan(new StyleSpan(android.graphics.Typeface.ITALIC),
            0, pos.length(), finalFlags);
        }
        if (entry.isVerb() && !entry.isPrefix() && !entry.isSuffix()) {
            // Reduce the size of the transitivity information.
            ssb.setSpan(new AbsoluteSizeSpan(14), transitivityStart,
                transitivityStart + transitivityHeader.length() +
                transitivity.length(), finalFlags);
        }
        if (!hiddenNotes.equals("")) {
            // Reduce the size of the hidden notes.
            ssb.setSpan(new AbsoluteSizeSpan(14), hiddenNotesStart,
                hiddenNotesStart + hiddenNotesHeader.length() +
                hiddenNotes.length(), finalFlags);
        }
        while (m.find()) {

            // Strip the brackets {} to get the query.
            String query = expandedDefinition.substring(m.start() + 1, m.end() - 1);
            LookupClickableSpan viewLauncher = new LookupClickableSpan(query);

            // Process the linked entry information.
            KlingonContentProvider.Entry linkedEntry = new KlingonContentProvider.Entry(query);
            // Log.d(TAG, "linkedEntry.getEntryName() = " + linkedEntry.getEntryName());

            // Delete the brackets and metadata parts of the string.
            ssb.delete(m.start() + 1 + linkedEntry.getEntryName().length(), m.end());
            ssb.delete(m.start(), m.start() + 1);

            // Set the font and link.
            // TODO: Source should link to description of the source.
            // This is true if this entry doesn't launch an EntryActivity.
            boolean disableEntryLink = linkedEntry.doNotLink() || linkedEntry.isSource();
            // The last span set on a range must have finalFlags.
            int maybeFinalFlags = disableEntryLink ? finalFlags : intermediateFlags;
            int end = m.start() + linkedEntry.getEntryName().length();
            if (linkedEntry.isSource()) {
                // Linkify URL if there is one.
                String url = linkedEntry.getSourceURL();
                if (!url.equals("")) {
                    ssb.setSpan(new URLSpan(url), m.start(), end, intermediateFlags);
                }
                // Names of sources are in italics.
                ssb.setSpan(new StyleSpan(android.graphics.Typeface.ITALIC), m.start(), end, maybeFinalFlags);
            } else {
                // Klingon is in bold serif.
                ssb.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), m.start(), end, intermediateFlags);
                ssb.setSpan(new TypefaceSpan("serif"), m.start(), end, maybeFinalFlags);
            }
            // If linked entry is hypothetical or extended canon, insert a "?" in front.
            if (linkedEntry.isHypothetical() || linkedEntry.isExtendedCanon()) {
                ssb.insert(m.start(), "?");
                ssb.setSpan(new AbsoluteSizeSpan(14), m.start(), m.start() + 1, intermediateFlags);
                ssb.setSpan(new SuperscriptSpan(), m.start(), m.start() + 1, maybeFinalFlags);
                end++;
            }
            if (!disableEntryLink) {
                // Link to view launcher.
                ssb.setSpan(viewLauncher, m.start(), end, finalFlags);
            }
            String linkedPos = linkedEntry.getBracketedPartOfSpeech(/* isHtml */ false);
            if (!linkedPos.equals("") && linkedPos.length() > 1) {
                ssb.insert(end, linkedPos);

                // linkedPos includes a space and brackets, skip them.
                ssb.setSpan(new StyleSpan(android.graphics.Typeface.ITALIC),
                    end + 2, end + linkedPos.length() - 1, finalFlags);
            }

            // Rinse and repeat.
            expandedDefinition = ssb.toString();
            m = KlingonContentProvider.Entry.ENTRY_PATTERN.matcher(expandedDefinition);
        }

        // Display the entry name and definition.
        entryText.setText(ssb);
        entryText.setMovementMethod(LinkMovementMethod.getInstance());

        // Activate type-to-search for local search.  Typing will automatically
        // start a search of the database.
        setDefaultKeyMode(DEFAULT_KEYS_SEARCH_LOCAL);
    }

    /*
    TODO: Override onSave/RestoreInstanceState, onPause/Resume/Stop, to re-create links.

    public onSaveInstanceState() {
        // Save the text and views here.
        super.onSaveInstanceState();
    }
    public onRestoreInstanceState() {
        // Restore the text and views here.
        super.onRestoreInstanceState();
    }
    */

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.options_menu, menu);

        // BACKPORT: No search view.

        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.search:
                onSearchRequested();
                return true;
            // BACKPORT: No handling of android.R.id.home.
            default:
                return false;
        }
    }

            // Private class for handling clickable spans.
    private class LookupClickableSpan extends ClickableSpan {
      private String mQuery;

      LookupClickableSpan(String query) {
        mQuery = query;
      }

      @Override
      public void onClick(View view) {
        Intent intent = new Intent();
        intent.setComponent(new ComponentName(
            "org.tlhInganHol.android.klingonassistant",
            "org.tlhInganHol.android.klingonassistant.KlingonAssistant"));
        intent.setAction(Intent.ACTION_SEARCH);
        intent.putExtra(SearchManager.QUERY, mQuery);

        view.getContext().startActivity(intent);
      }
    }
}