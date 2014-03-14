/* -*- compile-command: "find-and-ant.sh debug install"; -*- */
/*
 * Copyright 2009 - 2012 by Eric House (xwords@eehouse.org).  All
 * rights reserved.
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
import android.app.ProgressDialog;
import android.content.DialogInterface.OnClickListener;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;
import java.util.HashMap;
import java.util.Iterator;

import junit.framework.Assert;

public class DlgDelegate {

    public static final int SMS_BTN = AlertDialog.BUTTON_POSITIVE;
    public static final int EMAIL_BTN = AlertDialog.BUTTON_NEGATIVE;
    public static final int NFC_BTN = AlertDialog.BUTTON_NEUTRAL;
    public static final int DISMISS_BUTTON = 0;
    public static final int SKIP_CALLBACK = -1;

    private static final String IDS = "IDS";
    private static final String STATE_KEYF = "STATE_%d";

    public interface DlgClickNotify {
        void dlgButtonClicked( int id, int button, Object[] params );
    }
    public interface HasDlgDelegate {
        void showOKOnlyDialog( int msgID );
        void showOKOnlyDialog( String msg );
    }

    private Activity m_activity;
    private DlgClickNotify m_clickCallback;
    private String m_dictName = null;
    private ProgressDialog m_progress;
    private Handler m_handler;

    private HashMap<DlgID, DlgState> m_dlgStates;

    public DlgDelegate( Activity activity, DlgClickNotify callback,
                        Bundle bundle ) 
    {
        m_activity = activity;
        m_clickCallback = callback;
        m_handler = new Handler();
        m_dlgStates = new HashMap<DlgID,DlgState>();

        if ( null != bundle ) {
            int[] ids = bundle.getIntArray( IDS );
            for ( int id : ids ) {
                String key = String.format( STATE_KEYF, id );
                addState( (DlgState)bundle.getParcelable( key ) );
            }
        }
    }

    public void onSaveInstanceState( Bundle outState ) 
    {
        int[] ids = new int[m_dlgStates.size()];
        if ( 0 < ids.length ) {
            int indx = 0;
            Iterator<DlgState> iter = m_dlgStates.values().iterator();
            while ( iter.hasNext() ) {
                DlgState state = iter.next();
                String key = String.format( STATE_KEYF, state.m_id );
                outState.putParcelable( key, state );
                ids[indx++] = state.m_id.ordinal();
            }
        }
        outState.putIntArray( IDS, ids );
    }
    
    public Dialog onCreateDialog( int id )
    {
        // DbgUtils.logf("onCreateDialog(id=%d)", id );
        Dialog dialog = null;
        DlgID dlgID = DlgID.values()[id];
        DlgState state = findForID( dlgID );
        switch( dlgID ) {
        case DIALOG_ABOUT:
            dialog = createAboutDialog();
            break;
        case DIALOG_OKONLY:
            dialog = createOKDialog( state, dlgID );
            break;
        case DIALOG_NOTAGAIN:
            dialog = createNotAgainDialog( state, dlgID );
            break;
        case CONFIRM_THEN:
            dialog = createConfirmThenDialog( state, dlgID );
            break;
        case INVITE_CHOICES_THEN:
            dialog = createInviteChoicesDialog( state, dlgID );
            break;
        case DLG_DICTGONE:
            dialog = createDictGoneDialog();
            break;
        }
        return dialog;
    }

    public void showOKOnlyDialog( String msg )
    {
        showOKOnlyDialog( msg, SKIP_CALLBACK );
    }

    public void showOKOnlyDialog( String msg, int callbackID )
    {
        // Assert.assertNull( m_dlgStates );
        DlgState state = new DlgState( DlgID.DIALOG_OKONLY, msg, callbackID );
        addState( state );
        m_activity.showDialog( DlgID.DIALOG_OKONLY.ordinal() );
    }

    public void showOKOnlyDialog( int msgID )
    {
        showOKOnlyDialog( m_activity.getString( msgID ), SKIP_CALLBACK );
    }

    public void showDictGoneFinish()
    {
        m_activity.showDialog( DlgID.DLG_DICTGONE.ordinal() );
    }

    public void showAboutDialog()
    {
        m_activity.showDialog( DlgID.DIALOG_ABOUT.ordinal() );
    }

    public void showNotAgainDlgThen( int msgID, int prefsKey,
                                     final int callbackID, 
                                     final Object[] params )
    {
        showNotAgainDlgThen( m_activity.getString( msgID ), prefsKey, 
                             callbackID, params );
    }

    public void showNotAgainDlgThen( String msg, int prefsKey,
                                     final int callbackID, 
                                     final Object[] params )
    {
        if ( XWPrefs.getPrefsBoolean( m_activity, prefsKey, false ) ) {
            // If it's set, do the action without bothering with the
            // dialog
            if ( SKIP_CALLBACK != callbackID ) {
                post( new Runnable() {
                        public void run() {
                            m_clickCallback
                                .dlgButtonClicked( callbackID, 
                                                   AlertDialog.BUTTON_POSITIVE,
                                                   params );
                        }
                    });
            }
        } else {
            DlgState state = 
                new DlgState( DlgID.DIALOG_NOTAGAIN, msg, callbackID, prefsKey, 
                              params );
            addState( state );
            m_activity.showDialog( DlgID.DIALOG_NOTAGAIN.ordinal() );
        }
    }

    public void showNotAgainDlgThen( int msgID, int prefsKey,
                                     int callbackID  )
    {
        showNotAgainDlgThen( msgID, prefsKey, callbackID, null );
    }

    public void showNotAgainDlgThen( int msgID, int prefsKey )
    {
        showNotAgainDlgThen( msgID, prefsKey, SKIP_CALLBACK );
    }

    public void showConfirmThen( String msg, int callbackID )
    {
        showConfirmThen( msg, R.string.button_ok, callbackID, null );
    }

    public void showConfirmThen( String msg, int callbackID, Object[] params )
    {
        showConfirmThen( msg, R.string.button_ok, callbackID, params );
    }

    public void showConfirmThen( String msg, int posButton, int callbackID )
    {
        showConfirmThen( msg, posButton, callbackID, null );
    }

    public void showConfirmThen( String msg, int posButton, int callbackID,
                                 Object[] params )
    {
        DlgState state = new DlgState( DlgID.CONFIRM_THEN, msg, posButton, 
                                       callbackID, 0, params );
        addState( state );
        m_activity.showDialog( DlgID.CONFIRM_THEN.ordinal() );
    }

    public void showInviteChoicesThen( final int callbackID )
    {
        if ( Utils.deviceSupportsSMS( m_activity )
             || NFCUtils.nfcAvail( m_activity )[0] ) {
            DlgState state = new DlgState( DlgID.INVITE_CHOICES_THEN, callbackID );
            addState( state );
            m_activity.showDialog( DlgID.INVITE_CHOICES_THEN.ordinal() );
        } else {
            post( new Runnable() {
                    public void run() {
                        m_clickCallback.dlgButtonClicked( callbackID, EMAIL_BTN,
                                                          null );
                    } 
                });
        }
    }

    public void doSyncMenuitem()
    {
        if ( null == DBUtils.getRelayIDs( m_activity, null ) ) {
            showOKOnlyDialog( R.string.no_games_to_refresh );
        } else {
            RelayService.timerFired( m_activity );
            Utils.showToast( m_activity, R.string.msgs_progress );
        }
    }

    public void launchLookup( String[] words, int lang, boolean noStudyOption )
    {
        LookupActivity.launch( m_activity, words, lang, noStudyOption );
    }

    public void startProgress( int id )
    {
        String msg = m_activity.getString( id );
        m_progress = ProgressDialog.show( m_activity, msg, null, true, true );
    }

    public void stopProgress()
    {
        if ( null != m_progress ) {
            m_progress.cancel();
            m_progress = null;
        }
    }

    public boolean post( Runnable runnable )
    {
        m_handler.post( runnable );
        return true;
    }

    public void eventOccurred( MultiService.MultiEvent event, final Object ... args )
    {
        String msg = null;
        boolean asToast = true;
        switch( event ) {
        case BAD_PROTO:
            msg = Utils.format( m_activity, R.string.bt_bad_protof,
                                       (String)args[0] );
            break;
        case MESSAGE_RESEND:
            msg = Utils.format( m_activity, R.string.bt_resendf,
                                (String)args[0], (Long)args[1], (Integer)args[2] );
            break;
        case MESSAGE_FAILOUT:
            msg = Utils.format( m_activity, R.string.bt_failf, 
                                (String)args[0] );
            asToast = false;
            break;
        case RELAY_ALERT:
            msg = (String)args[0];
            asToast = false;
            break;

        default:
            DbgUtils.logf( "eventOccurred: unhandled event %s", event.toString() );
        }

        if ( null != msg ) {
            final String fmsg = msg;
            final boolean asDlg = !asToast;
            post( new Runnable() {
                    public void run() {
                        if ( asDlg ) {
                            showOKOnlyDialog( fmsg, SKIP_CALLBACK );
                        } else {
                            DbgUtils.showf( m_activity, fmsg );
                        }
                    }
                } );
        }
    }

    private Dialog createAboutDialog()
    {
        final View view = Utils.inflate( m_activity, R.layout.about_dlg );
        TextView vers = (TextView)view.findViewById( R.id.version_string );
        vers.setText( String.format( m_activity.getString(R.string.about_versf), 
                                     m_activity.getString(R.string.app_version),
                                     BuildConstants.GIT_REV, 
                                     BuildConstants.BUILD_STAMP ) );

        TextView xlator = (TextView)view.findViewById( R.id.about_xlator );
        String str = m_activity.getString( R.string.xlator );
        if ( str.length() > 0 ) {
            xlator.setText( str );
        } else {
            xlator.setVisibility( View.GONE );
        }

        return new AlertDialog.Builder( m_activity )
            .setIcon( R.drawable.icon48x48 )
            .setTitle( R.string.app_name )
            .setView( view )
            .setNegativeButton( R.string.changes_button,
                                new OnClickListener() {
                                    @Override
                                    public void onClick( DialogInterface dlg, 
                                                         int which )
                                    {
                                        FirstRunDialog.show( m_activity );
                                    }
                                } )
            .setPositiveButton( R.string.button_ok, null )
            .create();
    }

    private Dialog createOKDialog( DlgState state, DlgID dlgID )
    {
        Dialog dialog = new AlertDialog.Builder( m_activity )
            .setTitle( R.string.info_title )
            .setMessage( state.m_msg )
            .setPositiveButton( R.string.button_ok, null )
            .create();
        dialog = setCallbackDismissListener( dialog, state, dlgID );

        return dialog;
    }

    private Dialog createNotAgainDialog( final DlgState state, DlgID dlgID )
    {
        OnClickListener lstnr_p = mkCallbackClickListener( state );

        OnClickListener lstnr_n = 
            new OnClickListener() {
                public void onClick( DialogInterface dlg, int item ) {
                    XWPrefs.setPrefsBoolean( m_activity, state.m_prefsKey, 
                                             true );
                    if ( SKIP_CALLBACK != state.m_cbckID ) {
                        m_clickCallback.
                            dlgButtonClicked( state.m_cbckID, 
                                              AlertDialog.BUTTON_POSITIVE, 
                                              state.m_params );
                    }
                }
            };

        Dialog dialog = new AlertDialog.Builder( m_activity )
            .setTitle( R.string.newbie_title )
            .setMessage( state.m_msg )
            .setPositiveButton( R.string.button_ok, lstnr_p )
            .setNegativeButton( R.string.button_notagain, lstnr_n )
            .create();

        return setCallbackDismissListener( dialog, state, dlgID );
    } // createNotAgainDialog

    private Dialog createConfirmThenDialog( DlgState state, DlgID dlgID )
    {
        OnClickListener lstnr = mkCallbackClickListener( state );

        Dialog dialog = new AlertDialog.Builder( m_activity )
            .setTitle( R.string.query_title )
            .setMessage( state.m_msg )
            .setPositiveButton( state.m_posButton, lstnr )
            .setNegativeButton( R.string.button_cancel, lstnr )
            .create();
        
        return setCallbackDismissListener( dialog, state, dlgID );
    }

    private Dialog createInviteChoicesDialog( DlgState state, DlgID dlgID )
    {
        OnClickListener lstnr = mkCallbackClickListener( state );

        boolean haveSMS = Utils.deviceSupportsSMS( m_activity );
        boolean haveNFC = NFCUtils.nfcAvail( m_activity )[0];
        int msgID;
        if ( haveSMS && haveNFC ) {
            msgID = R.string.nfc_or_sms_or_email;
        } else if ( haveSMS ) {
            msgID = R.string.sms_or_email; 
        } else {
            msgID = R.string.nfc_or_email;
        }
        
        AlertDialog.Builder builder = new AlertDialog.Builder( m_activity )
            .setTitle( R.string.query_title )
            .setMessage( msgID )
            .setNegativeButton( R.string.button_html, lstnr );

        if ( haveSMS ) {
            builder.setPositiveButton( R.string.button_text, lstnr );
        }
        if ( haveNFC ) {
            builder.setNeutralButton( R.string.button_nfc, lstnr );
        }

        return setCallbackDismissListener( builder.create(), state, dlgID );
    }

    private Dialog createDictGoneDialog()
    {
        Dialog dialog = new AlertDialog.Builder( m_activity )
            .setTitle( R.string.no_dict_title )
            .setMessage( R.string.no_dict_finish )
            .setPositiveButton( R.string.button_close_game, null )
            .create();

        dialog.setOnDismissListener( new DialogInterface.OnDismissListener() {
                public void onDismiss( DialogInterface di ) {
                    m_activity.finish();
                }
            } );

        return dialog;
    }

    private OnClickListener mkCallbackClickListener( final DlgState state )
    {
        OnClickListener cbkOnClickLstnr;
        cbkOnClickLstnr = new OnClickListener() {
                public void onClick( DialogInterface dlg, int button ) {
                    if ( SKIP_CALLBACK != state.m_cbckID ) {
                        m_clickCallback.dlgButtonClicked( state.m_cbckID, 
                                                          button, 
                                                          state.m_params );
                    }
                }
            };
        return cbkOnClickLstnr;
    }

    private Dialog setCallbackDismissListener( final Dialog dialog, 
                                               final DlgState state,
                                               DlgID dlgID )
    {
        final int id = dlgID.ordinal();
        DialogInterface.OnDismissListener cbkOnDismissLstnr
            = new DialogInterface.OnDismissListener() {
                    public void onDismiss( DialogInterface di ) {
                        dropState( state );
                        if ( SKIP_CALLBACK != state.m_cbckID ) {
                            m_clickCallback.dlgButtonClicked( state.m_cbckID, 
                                                              DISMISS_BUTTON, 
                                                              state.m_params );
                        }
                        m_activity.removeDialog( id );
                    }
                };

        dialog.setOnDismissListener( cbkOnDismissLstnr );
        return dialog;
    }

    private DlgState findForID( DlgID dlgID )
    {
        DlgState state = m_dlgStates.get( dlgID );
        // DbgUtils.logf( "findForID(%d)=>%H", id, state );
        return state;
    }

    private void dropState( DlgState state )
    {
        int nDlgs = m_dlgStates.size();
        Assert.assertNotNull( state );
        // Assert.assertTrue( state == m_dlgStates.get( state.m_id ) );
        m_dlgStates.remove( state.m_id );
        // DbgUtils.logf( "dropState: active dialogs now %d from %d ", 
        //                m_dlgStates.size(), nDlgs );
    }

    private void addState( DlgState state )
    {
        // I'm getting serialization failures on devices pointing at
        // DlgState but the code below says the object's fine (as it
        // should be.)  Just to have a record....
        // 
        // Bundle bundle = new Bundle();
        // DbgUtils.logf( "addState: testing serializable" );
        // bundle.putSerializable( "foo", state );
        // state = (DlgState)bundle.getSerializable( "foo" );
        // DbgUtils.logf( "addState: serializable is ok" );

        m_dlgStates.put( state.m_id, state );
    }

}
