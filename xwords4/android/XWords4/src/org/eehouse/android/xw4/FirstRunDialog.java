/* -*- compile-command: "find-and-ant.sh debug install"; -*- */
/*
 * Copyright 2010 by Eric House (xwords@eehouse.org).  All rights
 * reserved.
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License as
 * published by the Free Software Foundation; either version 2 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */

package org.eehouse.android.xw4;

import android.app.AlertDialog;
import android.content.Context;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;

import org.eehouse.android.xw4.loc.LocUtils;

/* Put up a dialog greeting user after every upgrade.  Based on
 * similar feature in OpenSudoku, to whose author "Thanks".
 */

public class FirstRunDialog {
    public static void show( final Context context )
    {
        final boolean showSurvey = !Utils.onFirstVersion( context );

        // This won't support e.g mailto refs.  Probably want to
        // launch the browser with an intent eventually.
        final WebView view = new WebView( context );
        view.setWebViewClient( new WebViewClient() {
                private boolean m_loaded = false;
                @Override
                public boolean shouldOverrideUrlLoading( WebView view, 
                                                         String url ) {
                    boolean result = false;
                    if ( url.startsWith("mailto:") ){
                        Utils.emailAuthor( context );
                        result = true;
                    }
                    return result;
                }
                @Override
                public void onPageFinished(WebView view, String url)
                {
                    if ( !m_loaded ) {
                        m_loaded = true;
                        if ( showSurvey ) {
                            view.loadUrl( "javascript:showSurvey();" );
                        }
                    }
                }
            });
        view.getSettings().setJavaScriptEnabled( true ); // for surveymonkey
        view.loadUrl("file:///android_asset/changes.html");

        AlertDialog dialog = LocUtils.makeAlertBuilder( context )
            .setIcon(android.R.drawable.ic_menu_info_details)
            .setTitle( R.string.changes_title )
            .setView( view )
            .setPositiveButton( R.string.button_ok, null)
            .create();
        dialog.show();
    }
}
