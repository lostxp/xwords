/* -*- compile-command: "find-and-ant.sh debug install"; -*- */
/*
 * Copyright 2009-2011 by Eric House (xwords@eehouse.org).  All
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
import android.app.ListActivity;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.ListView;
import android.widget.TextView;
import android.os.Handler;

import junit.framework.Assert;

public class BTInviteDelegate extends InviteDelegate
    implements CompoundButton.OnCheckedChangeListener {

    private Activity m_activity;
    private boolean m_firstScan;
    private int m_checkCount;

    public static void launchForResult( Activity activity, int nMissing, 
                                        int requestCode )
    {
        Intent intent = new Intent( activity, BTInviteActivity.class );
        intent.putExtra( INTENT_KEY_NMISSING, nMissing );
        activity.startActivityForResult( intent, requestCode );
    }

    protected BTInviteDelegate( ListDelegator delegator, Bundle savedInstanceState )
    {
        super( delegator, savedInstanceState, R.layout.btinviter );
        m_activity = delegator.getActivity();
    }

    protected void init()
    {
        super.init( R.id.button_invite, R.id.button_rescan, 
                    R.id.button_clear, R.id.invite_desc,
                    R.string.invite_bt_desc_fmt );
        m_firstScan = true;
        BTService.clearDevices( m_activity, null ); // will return names
    }

    // MultiService.MultiEventListener interface
    @Override
    public void eventOccurred( MultiService.MultiEvent event, final Object ... args )
    {
        switch( event ) {
        case SCAN_DONE:
            post( new Runnable() {
                    public void run() {
                        synchronized( BTInviteDelegate.this ) {
                            stopProgress();

                            String[] btDevNames = null;
                            if ( 0 < args.length ) {
                                btDevNames = (String[])(args[0]);
                                if ( null != btDevNames
                                     && 0 == btDevNames.length ) {
                                    btDevNames = null;
                                }
                            }

                            if ( null == btDevNames && m_firstScan ) {
                                BTService.scan( m_activity );
                            }
                            setListAdapter( new BTDevsAdapter( btDevNames ) );
                            m_checkCount = 0;
                            tryEnable();
                            m_firstScan = false;
                        }
                    }
                } );
            break;
        default:
            super.eventOccurred( event, args );
        }
    }

    protected void scan()
    {
        startProgress( R.string.scan_progress );
        BTService.scan( m_activity );
    }

    protected void clearSelected()
    {
        BTService.clearDevices( m_activity, listSelected() );
    }

    protected String[] listSelected()
    {
        ListView list = (ListView)findViewById( android.R.id.list );
        String[] result = new String[m_checkCount];
        int count = list.getChildCount();
        int index = 0;
        for ( int ii = 0; ii < count; ++ii ) {
            CheckBox box = (CheckBox)list.getChildAt( ii );
            if ( box.isChecked() ) {
                result[index++] = box.getText().toString();
            }
        }
        return result;
    }

    protected void tryEnable() 
    {
        m_okButton.setEnabled( m_checkCount == m_nMissing );
        m_clearButton.setEnabled( 0 < m_checkCount );
    }

    public void onCheckedChanged( CompoundButton buttonView, 
                                  boolean isChecked )
    {
        if ( isChecked ) {
            ++m_checkCount;
        } else {
            --m_checkCount;
        }
        DbgUtils.logf( "BTInviteActivity.onCheckedChanged( isChecked=%b ); "
                       + "count now %d", isChecked, m_checkCount );
        tryEnable();
    }

    private class BTDevsAdapter extends XWListAdapter {
        private String[] m_devs;
        public BTDevsAdapter( String[] devs )
        {
            super( null == devs? 0 : devs.length );
            m_devs = devs;
        }

        public Object getItem( int position) { return m_devs[position]; }
        public View getView( final int position, View convertView, 
                             ViewGroup parent ) {
            CheckBox box = (CheckBox)inflate( R.layout.btinviter_item );
            box.setText( m_devs[position] );
            box.setOnCheckedChangeListener( BTInviteDelegate.this );
            return box;
        }
    }
}
