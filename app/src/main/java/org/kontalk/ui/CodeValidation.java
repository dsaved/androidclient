/*
 * Kontalk Android client
 * Copyright (C) 2016 Kontalk Devteam <devteam@kontalk.org>

 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.

 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.

 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.kontalk.ui;

import com.afollestad.materialdialogs.DialogAction;
import com.afollestad.materialdialogs.MaterialDialog;

import android.content.Intent;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.v4.content.ContextCompat;
import android.util.Log;
import android.view.MenuItem;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.kontalk.R;
import org.kontalk.client.EndpointServer;
import org.kontalk.client.NumberValidator;
import org.kontalk.client.NumberValidator.NumberValidatorListener;
import org.kontalk.crypto.PersonalKey;
import org.kontalk.provider.UsersProvider;
import org.kontalk.service.KeyPairGeneratorService;
import org.kontalk.util.Preferences;
import org.kontalk.util.SystemUtils;

import java.net.SocketException;
import java.util.HashMap;
import java.util.Map;


/** Manual validation code input. */
public class CodeValidation extends AccountAuthenticatorActionBarActivity
        implements NumberValidatorListener {
    private static final String TAG = NumberValidation.TAG;

    private EditText mCode;
    private Button mButton;
    private Button mFallbackButton;
    private ProgressBar mProgress;

    private NumberValidator mValidator;
    private PersonalKey mKey;
    private String mName;
    private String mPhone;
    private String mPassphrase;
    boolean mForce;
    private EndpointServer.EndpointServerProvider mServerProvider;

    private byte[] mImportedPrivateKey;
    private byte[] mImportedPublicKey;
    Map<String, String> mTrustedKeys;

    private static final class RetainData {
        NumberValidator validator;
        Map<String, String> trustedKeys;

        RetainData() {
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        supportRequestWindowFeature(Window.FEATURE_INDETERMINATE_PROGRESS);
        setContentView(R.layout.code_validation_screen);
        setupToolbar(true);

        mCode = (EditText) findViewById(R.id.validation_code);
        mButton = (Button) findViewById(R.id.send_button);
        mFallbackButton = (Button) findViewById(R.id.fallback_button);
        mProgress = (ProgressBar) findViewById(R.id.progressbar);

        // configuration change??
        RetainData data = (RetainData) getLastCustomNonConfigurationInstance();
        if (data != null) {
            mValidator = data.validator;
            if (mValidator != null) {
                startProgress();
                mValidator.setListener(this);
            }
            mTrustedKeys = data.trustedKeys;
        }

        int requestCode = getIntent().getIntExtra("requestCode", -1);
        if (requestCode == NumberValidation.REQUEST_VALIDATION_CODE ||
                getIntent().getStringExtra("sender") == null) {
            findViewById(R.id.code_validation_sender)
                .setVisibility(View.GONE);
            findViewById(R.id.code_validation_intro2)
                .setVisibility(View.GONE);
            ((TextView) findViewById(R.id.code_validation_intro))
                .setText(R.string.code_validation_intro_manual);
        }
        else {
            String challenge = getIntent().getStringExtra("challenge");
            String sender = getIntent().getStringExtra("sender");

            final TextView senderText = (TextView) findViewById(R.id.code_validation_sender);
            final Button senderCall = (Button) findViewById(R.id.code_validation_call);

            CharSequence textId1, textId2;
            if (NumberValidator.isMissedCall(sender) || NumberValidator.CHALLENGE_MISSED_CALL.equals(challenge)) {
                // reverse missed call
                textId1 = getText(R.string.code_validation_intro_missed_call);
                textId2 = getString(R.string.code_validation_intro2_missed_call,
                    NumberValidator.getChallengeLength(sender));
                mFallbackButton.setText(R.string.button_validation_fallback);
                mFallbackButton.setVisibility(View.VISIBLE);
                // show sender label and hide call button
                senderText.setText(sender);
                senderText.setVisibility(View.VISIBLE);
                senderCall.setVisibility(View.GONE);
                mCode.setVisibility(View.VISIBLE);
            }
            else if (NumberValidator.CHALLENGE_CALLER_ID.equals(challenge)) {
                // user-initiated missed call
                textId1 = getText(R.string.code_validation_intro_callerid);
                textId2 = getText(R.string.code_validation_intro2_callerid);
                mFallbackButton.setText(R.string.button_validation_fallback_callerid);
                mFallbackButton.setVisibility(View.VISIBLE);
                // show call button and hide sender label
                senderCall.setText(sender);
                senderCall.setVisibility(View.VISIBLE);
                senderText.setVisibility(View.GONE);
                mCode.setVisibility(View.GONE);
            }
            else {
                // PIN code
                textId1 = getText(R.string.code_validation_intro);
                textId2 = getText(R.string.code_validation_intro2);
                mFallbackButton.setVisibility(View.GONE);
                // show sender label and hide call button
                senderText.setText(sender);
                senderText.setVisibility(View.VISIBLE);
                senderCall.setVisibility(View.GONE);
                mCode.setVisibility(View.VISIBLE);
            }

            if (senderCall.getVisibility() == View.VISIBLE) {
                senderCall.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        SystemUtils.dial(CodeValidation.this, senderCall.getText());
                    }
                });
            }

            ((TextView) findViewById(R.id.code_validation_intro)).setText(textId1);
            ((TextView) findViewById(R.id.code_validation_intro2)).setText(textId2);
        }

        Intent i = getIntent();
        mKey = i.getParcelableExtra(KeyPairGeneratorService.EXTRA_KEY);
        mName = i.getStringExtra("name");
        mPhone = i.getStringExtra("phone");
        mForce = i.getBooleanExtra("force", false);
        mPassphrase = i.getStringExtra("passphrase");
        mImportedPrivateKey = i.getByteArrayExtra("importedPrivateKey");
        mImportedPublicKey = i.getByteArrayExtra("importedPublicKey");
        mTrustedKeys = (HashMap) i.getSerializableExtra("trustedKeys");

        String server = i.getStringExtra("server");
        if (server != null)
            mServerProvider = new EndpointServer.SingleServerProvider(server);
        else
            /*
             * FIXME HUGE problem here. If we already have a verification code,
             * how are we supposed to know from what server it came from??
             * https://github.com/kontalk/androidclient/issues/118
             */
            mServerProvider = Preferences.getEndpointServerProvider(this);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home:
                onBackPressed();
                return true;

            default:
                return super.onOptionsItemSelected(item);
        }
    }

    @Override
    public void onBackPressed() {
        new MaterialDialog.Builder(this)
            .title(R.string.title_confirm_cancel_registration)
            .content(R.string.confirm_cancel_registration)
            .positiveText(android.R.string.ok)
            .positiveColorRes(R.color.button_danger)
            .negativeText(android.R.string.cancel)
            .onPositive(new MaterialDialog.SingleButtonCallback() {
                @Override
                public void onClick(@NonNull MaterialDialog materialDialog, @NonNull DialogAction dialogAction) {
                    // we are going back voluntarily
                    Preferences.clearRegistrationProgress(CodeValidation.this);
                    CodeValidation.super.onBackPressed();
                }
            })
            .show();
    }

    /** No search here. */
    @Override
    public boolean onSearchRequested() {
        return false;
    }

    /** Returning the validator thread. */
    @Override
    public Object onRetainCustomNonConfigurationInstance() {
        RetainData data = new RetainData();
        data.validator = mValidator;
        data.trustedKeys = mTrustedKeys;
        return data;
    }

    @Override
    protected void onStart() {
        super.onStart();
        // start a users resync in the meantime
        UsersProvider.resync(this);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (isFinishing())
            abort(true);
    }

    void keepScreenOn(boolean active) {
        if (active)
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void error(int message) {
        new MaterialDialog.Builder(this)
            .content(message)
            .positiveText(android.R.string.ok)
            .show();
    }

    public void doFallback(View view) {
        new MaterialDialog.Builder(this)
            .title(R.string.title_fallback)
            .content(R.string.msg_fallback)
            .icon(ContextCompat.getDrawable(this, android.R.drawable.ic_dialog_info))
            .positiveText(android.R.string.ok)
            .onPositive(new MaterialDialog.SingleButtonCallback() {
                @Override
                public void onClick(@NonNull MaterialDialog dialog, @NonNull DialogAction which) {
                    Intent i = new Intent();
                    i.putExtra("force", mForce);
                    setResult(NumberValidation.RESULT_FALLBACK, i);
                    finish();
                }
            })
            .negativeText(android.R.string.cancel)
            .show();
    }

    public void validateCode(View view) {
        String code = mCode.getText().toString().trim();
        if (code.length() == 0) {
            error(R.string.msg_invalid_code);
            return;
        }

        startProgress();

        // send the code
        boolean imported = (mImportedPrivateKey != null && mImportedPublicKey != null);
        mServerProvider.reset();
        mValidator = new NumberValidator(this, mServerProvider, mName, mPhone,
            imported ? null : mKey, mPassphrase);
        mValidator.setListener(this);
        if (imported)
            mValidator.importKey(mImportedPrivateKey, mImportedPublicKey);

        mValidator.manualInput(code);
        mValidator.start();
    }

    private void enableControls(boolean enabled) {
        mButton.setEnabled(enabled);
        mFallbackButton.setEnabled(enabled);
        mCode.setEnabled(enabled);
    }

    private void startProgress() {
        mProgress.setVisibility(View.VISIBLE);
        enableControls(false);
        keepScreenOn(true);
    }

    void abort(boolean ending) {
        if (!ending) {
            mProgress.setVisibility(View.GONE);
            enableControls(true);
        }
        else {
            // ending - clear registration progress
            Preferences.clearRegistrationProgress(this);
        }
        keepScreenOn(false);
        if (mValidator != null) {
            mValidator.shutdown();
            mValidator = null;
        }
    }

    @Override
    public void onError(NumberValidator v, final Throwable e) {
        Log.e(TAG, "validation error.", e);
        runOnUiThread(new Runnable() {
            public void run() {
                int msgId;
                if (e instanceof SocketException)
                    msgId = R.string.err_validation_network_error;
                else
                    msgId = R.string.err_validation_error;
                Toast.makeText(CodeValidation.this, msgId, Toast.LENGTH_LONG).show();
                abort(false);
            }
        });
    }

    @Override
    public void onServerCheckFailed(NumberValidator v) {
        runOnUiThread(new Runnable() {
            public void run() {
                Toast.makeText(CodeValidation.this, R.string.err_validation_server_not_supported, Toast.LENGTH_LONG).show();
                abort(false);
            }
        });
    }

    @Override
    public void onValidationRequested(NumberValidator v, String sender, String challenge) {
        // not used.
    }

    @Override
    public void onValidationFailed(NumberValidator v, int reason) {
        // not used.
    }

    @Override
    public void onAuthTokenReceived(final NumberValidator v, final byte[] privateKeyData, final byte[] publicKeyData) {
        Log.d(TAG, "got authentication token!");

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                abort(true);
                Intent i = new Intent();
                i.putExtra(NumberValidation.PARAM_SERVER_URI, v.getServer().toString());
                i.putExtra(NumberValidation.PARAM_PUBLICKEY, publicKeyData);
                i.putExtra(NumberValidation.PARAM_PRIVATEKEY, privateKeyData);
                i.putExtra(NumberValidation.PARAM_TRUSTED_KEYS, (HashMap) mTrustedKeys);
                setResult(RESULT_OK, i);
                finish();
            }
        });
    }

    @Override
    public void onAuthTokenFailed(NumberValidator v, int reason) {
        Log.e(TAG, "authentication token request failed (" + reason + ")");
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                keepScreenOn(false);
                int resId;
                String challenge = getIntent().getStringExtra("challenge");
                if (NumberValidator.CHALLENGE_CALLER_ID.equals(challenge)) {
                    // we are verifying through user-initiated missed call
                    // notify the user that the verification didn't succeed
                    resId = R.string.err_authentication_failed_callerid;
                }
                else {
                    // we are verifying through PIN-based challenge
                    // notify the user that the challenge code wasn't accepted
                    resId = R.string.err_authentication_failed;
                }

                Toast.makeText(CodeValidation.this, resId, Toast.LENGTH_LONG).show();
                abort(false);
            }
        });
    }

}
