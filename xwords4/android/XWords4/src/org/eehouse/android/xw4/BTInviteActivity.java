/* -*- compile-command: "cd ../../../../../; ant debug install"; -*- */
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

public class BTInviteActivity extends XWListActivity 
    implements View.OnClickListener, 
               CompoundButton.OnCheckedChangeListener {

    public static final String DEVS = "DEVS";
    public static final String INTENT_KEY_NMISSING = "NMISSING";

    private Button m_okButton;
    private Button m_rescanButton;
    private Button m_clearButton;
    private int m_nMissing;
    private int m_checkCount = 0;
    private boolean m_firstScan;

    @Override
    protected void onCreate( Bundle savedInstanceState )
    {
        super.onCreate( savedInstanceState );

        Intent intent = getIntent();
        m_nMissing = intent.getIntExtra( INTENT_KEY_NMISSING, -1 );

        setContentView( R.layout.btinviter );

        TextView desc = (TextView)findViewById( R.id.invite_desc );
        desc.setText( Utils.format( this, R.string.invite_descf, m_nMissing ) );

        m_okButton = (Button)findViewById( R.id.button_invite );
        m_okButton.setOnClickListener( this );
        m_rescanButton = (Button)findViewById( R.id.button_rescan );
        m_rescanButton.setOnClickListener( this );
        m_clearButton = (Button)findViewById( R.id.button_clear );
        m_clearButton.setOnClickListener( this );

        m_checkCount = 0;
        tryEnable();

        m_firstScan = true;
        BTService.clearDevices( this, null ); // will return names
    }

    public void onClick( View view ) 
    {
        if ( m_okButton == view ) {
            Intent intent = new Intent();
            String[] devs = listSelected();
            intent.putExtra( DEVS, devs );
            setResult( Activity.RESULT_OK, intent );
            finish();
        } else if ( m_rescanButton == view ) {
            scan();
        } else if ( m_clearButton == view ) {
            BTService.clearDevices( this, listSelected() );
        }
    }

    // /* AdapterView.OnItemClickListener */
    // public void onItemClick( AdapterView<?> parent, View view, 
    //                          int position, long id )
    // {
    //     DbgUtils.logf( "BTInviteActivity.onItemClick(position=%d)", position );
    // }

    public void onCheckedChanged( CompoundButton buttonView, 
                                  boolean isChecked )
    {
        DbgUtils.logf( "BTInviteActivity.onCheckedChanged( isChecked=%b )",
                       isChecked );
        if ( isChecked ) {
            ++m_checkCount;
        } else {
            --m_checkCount;
        }
        tryEnable();
    }

    // BTService.BTEventListener interface
    @Override
    public void eventOccurred( BTService.BTEvent event, final Object ... args )
    {
        switch( event ) {
        case SCAN_DONE:
            post( new Runnable() {
                    public void run() {
                        synchronized( BTInviteActivity.this ) {
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
                                BTService.scan( BTInviteActivity.this );
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

    private void scan()
    {
        startProgress( R.string.scan_progress );
        BTService.scan( this );
    }

    private String[] listSelected()
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

    private void tryEnable() 
    {
        m_okButton.setEnabled( m_checkCount == m_nMissing );
        m_clearButton.setEnabled( 0 < m_checkCount );
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
            CheckBox box = (CheckBox)
                Utils.inflate( BTInviteActivity.this, R.layout.btinviter_item );
            box.setText( m_devs[position] );
            box.setOnCheckedChangeListener( BTInviteActivity.this );
            return box;
        }

    }

}