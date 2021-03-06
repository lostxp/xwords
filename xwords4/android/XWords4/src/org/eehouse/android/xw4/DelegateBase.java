/* -*- compile-command: "find-and-ant.sh debug install"; -*- */
/*
 * Copyright 2014 by Eric House (xwords@eehouse.org).  All rights reserved.
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

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.DialogInterface.OnCancelListener;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;

import org.eehouse.android.xw4.DlgDelegate.Action;
import org.eehouse.android.xw4.loc.LocUtils;

import junit.framework.Assert;

public class DelegateBase implements DlgDelegate.DlgClickNotify,
                                     DlgDelegate.HasDlgDelegate,
                                     MultiService.MultiEventListener {

    private DlgDelegate m_delegate;
    private Delegator m_delegator;
    private Activity m_activity;
    private int m_optionsMenuID;
    private int m_layoutID;
    private View m_rootView;

    public DelegateBase( Delegator delegator, Bundle bundle, int layoutID )
    {
        this( delegator, bundle, layoutID, R.menu.empty );
    }

    public DelegateBase( Delegator delegator, Bundle bundle, 
                         int layoutID, int menuID )
    {
        Assert.assertTrue( 0 < menuID );
        m_delegator = delegator;
        m_activity = delegator.getActivity();
        m_delegate = new DlgDelegate( m_activity, this, this, bundle );
        m_layoutID = layoutID;
        m_optionsMenuID = menuID;
        LocUtils.xlateTitle( m_activity );
    }

    // Does nothing unless overridden. These belong in an interface.
    protected void init( Bundle savedInstanceState ) { Assert.fail(); }
    protected void onSaveInstanceState( Bundle outState ) {}
    public boolean onPrepareOptionsMenu( Menu menu ) { return false; }
    public boolean onOptionsItemSelected( MenuItem item ) { return false; }
    protected void onStart() {}
    protected void onResume() {}
    protected void onPause() {}
    protected void onStop() {}
    protected void onDestroy() {}
    protected void onWindowFocusChanged( boolean hasFocus ) {}
    protected boolean onBackPressed() { return false; }
    protected void prepareDialog( DlgID dlgID, Dialog dialog ) {}
    protected void onActivityResult( int requestCode, int resultCode, 
                                     Intent data ) {}

    public boolean onCreateOptionsMenu( Menu menu, MenuInflater inflater )
    {
        boolean handled = 0 < m_optionsMenuID;
        if ( handled ) {
            inflater.inflate( m_optionsMenuID, menu );
            LocUtils.xlateMenu( m_activity, menu );
        } else {
            Assert.fail();
        }

        return handled;
    }

    public boolean onCreateOptionsMenu( Menu menu )
    {
        MenuInflater inflater = m_activity.getMenuInflater();
        return onCreateOptionsMenu( menu, inflater );
    }

    protected Intent getIntent()
    {
        return m_activity.getIntent();
    }

    protected int getLayoutID()
    {
        return m_layoutID;
    }

    protected Bundle getArguments()
    {
        return m_delegator.getArguments();
    }

    protected View getContentView()
    {
        return m_rootView;
    }

    protected void setContentView( View view )
    {
        LocUtils.xlateView( m_activity, view );
        m_rootView = view;
    }

    protected void setContentView( int resID )
    {
        m_activity.setContentView( resID );
        m_rootView = Utils.getContentView( m_activity );
        LocUtils.xlateView( m_activity, m_rootView );
    }

    protected View findViewById( int resID )
    {
        return m_rootView.findViewById( resID );
    }

    protected void setTitle( String title )
    {
        m_activity.setTitle( title );
    }

    protected String getTitle()
    {
        return m_activity.getTitle().toString();
    }

    protected void startActivityForResult( Intent intent, int requestCode )
    {
        m_activity.startActivityForResult( intent, requestCode );
    }

    protected void setResult( int result, Intent intent )
    {
        m_activity.setResult( result, intent );
    }

    protected void setResult( int result )
    {
        m_activity.setResult( result );
    }

    protected void onContentChanged()
    {
        m_activity.onContentChanged();
    }

    protected void startActivity( Intent intent )
    {
        m_activity.startActivity( intent );
    }

    protected void finish()
    {
        m_activity.finish();
    }

    protected String getString( int resID, Object... params )
    {
        return LocUtils.getString( m_activity, resID, params );
    }

    protected String[] getStringArray( int resID )
    {
        return LocUtils.getStringArray( m_activity, resID );
    }

    protected View inflate( int resID )
    {
        return LocUtils.inflate( m_activity, resID );
    }

    public void invalidateOptionsMenuIf()
    {
        ABUtils.invalidateOptionsMenuIf( m_activity );
    }

    public void showToast( int msg )
    {
        Utils.showToast( m_activity, msg );
    }

    public void showToast( String msg )
    {
        Utils.showToast( m_activity, msg );
    }

    public Object getSystemService( String name )
    {
        return m_activity.getSystemService( name );
    }

    public void runOnUiThread( Runnable runnable )
    {
        m_activity.runOnUiThread( runnable );
    }

    public void setText( int id, String value )
    {
        EditText editText = (EditText)findViewById( id );
        if ( null != editText ) {
            editText.setText( value, TextView.BufferType.EDITABLE );
        }
    }

    public String getText( int id )
    {
        EditText editText = (EditText)findViewById( id );
        return editText.getText().toString();
    }

    public void setInt( int id, int value )
    {
        String str = Integer.toString( value );
        setText( id, str );
    }

    public int getInt( int id )
    {
        int result = 0;
        String str = getText( id );
        try {
            result = Integer.parseInt( str );
        } catch ( NumberFormatException nfe ) {
        }
        return result;
    }


    public void setChecked( int id, boolean value )
    {
        CheckBox cbx = (CheckBox)findViewById( id );
        cbx.setChecked( value );
    }

    public boolean getChecked( int id )
    {
        CheckBox cbx = (CheckBox)findViewById( id );
        return cbx.isChecked();
    }

    protected void showDialog( DlgID dlgID )
    {
        m_delegate.showDialog( dlgID );
    }

    protected void removeDialog( DlgID dlgID )
    {
        removeDialog( dlgID.ordinal() );
    }

    protected void dismissDialog( DlgID dlgID )
    {
        m_activity.dismissDialog( dlgID.ordinal() );
    }

    protected void removeDialog( int id )
    {
        m_activity.removeDialog( id );
    }

    protected Dialog onCreateDialog( int id )
    {
        return m_delegate.createDialog( id );
    }

    protected AlertDialog.Builder makeAlertBuilder()
    {
        return LocUtils.makeAlertBuilder( m_activity );
    }

    protected void setRemoveOnDismiss( Dialog dialog, DlgID dlgID )
    {
        Utils.setRemoveOnDismiss( m_activity, dialog, dlgID );
    }

    protected void showNotAgainDlgThen( int msgID, int prefsKey,
                                        Action action, Object... params )
    {
        m_delegate.showNotAgainDlgThen( msgID, prefsKey, action, params );
    }

    public void showNotAgainDlgThen( int msgID, int prefsKey, Action action )
    {
        m_delegate.showNotAgainDlgThen( msgID, prefsKey, action );
    }

    protected void showNotAgainDlgThen( String msg, int prefsKey,
                                        Action action )
    {
        m_delegate.showNotAgainDlgThen( msg, prefsKey, action, null );
    }

    protected void showNotAgainDlg( int msgID, int prefsKey )
    {
        m_delegate.showNotAgainDlgThen( msgID, prefsKey );
    }

    protected void showNotAgainDlgThen( int msgID, int prefsKey )
    {
        m_delegate.showNotAgainDlgThen( msgID, prefsKey );
    }

    // It sucks that these must be duplicated here and XWActivity
    protected void showAboutDialog()
    {
        m_delegate.showAboutDialog();
    }

    public void showOKOnlyDialog( int msgID )
    {
        m_delegate.showOKOnlyDialog( msgID );
    }

    public void showOKOnlyDialog( String msg )
    {
        m_delegate.showOKOnlyDialog( msg );
    }

    protected void showConfirmThen( String msg, Action action, Object... params )
    {
        m_delegate.showConfirmThen( msg, action, params );
    }

    protected void showConfirmThen( String msg, int posButton, Action action,
                                    Object... params )
    {
        m_delegate.showConfirmThen( msg, posButton, action, params );
    }

    protected void showConfirmThen( int msg, int posButton, Action action, 
                                    Object... params )
    {
        m_delegate.showConfirmThen( msg, posButton, action, params );
    }

    protected void showConfirmThen( int msgID, Action action )
    {
        m_delegate.showConfirmThen( msgID, action );
    }

    protected boolean post( Runnable runnable )
    {
        return m_delegate.post( runnable );
    }

    protected void doSyncMenuitem()
    {
        m_delegate.doSyncMenuitem();
    }

    protected void launchLookup( String[] words, int lang, boolean noStudy )
    {
        m_delegate.launchLookup( words, lang, noStudy );
    }

    protected void launchLookup( String[] words, int lang )
    {
        boolean studyOn = XWPrefs.getStudyEnabled( m_activity );
        m_delegate.launchLookup( words, lang, !studyOn );
    }

    protected void showInviteChoicesThen( Action action )
    {
        m_delegate.showInviteChoicesThen( action );
    }

    protected void showOKOnlyDialogThen( String msg, Action action )
    {
        m_delegate.showOKOnlyDialog( msg, action );
    }

    protected void startProgress( int id )
    {
        m_delegate.startProgress( id );
    }

    protected void startProgress( int id, OnCancelListener lstnr )
    {
        m_delegate.startProgress( id, lstnr );
    }

    protected void setProgressMsg( int id )
    {
        m_delegate.setProgressMsg( id );
    }

    protected void stopProgress()
    {
        m_delegate.stopProgress();
    }

    protected void showDictGoneFinish()
    {
        m_delegate.showDictGoneFinish();
    }

    //////////////////////////////////////////////////
    // MultiService.MultiEventListener interface
    //////////////////////////////////////////////////
    public void eventOccurred( MultiService.MultiEvent event, final Object ... args )
    {
        Assert.fail();
    }

    //////////////////////////////////////////////////////////////////////
    // DlgDelegate.DlgClickNotify interface
    //////////////////////////////////////////////////////////////////////
    public void dlgButtonClicked( Action action, int button, Object[] params )
    {
        Assert.fail();
    }

}
