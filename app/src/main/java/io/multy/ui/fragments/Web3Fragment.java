/*
 * Copyright 2018 Idealnaya rabota LLC
 * Licensed under Multy.io license.
 * See LICENSE for details
 */

package io.multy.ui.fragments;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Toast;

import org.angmarch.views.NiceSpinner;

import java.util.List;

import butterknife.BindView;
import butterknife.ButterKnife;
import io.multy.R;
import io.multy.api.MultyApi;
import io.multy.model.entities.wallet.Wallet;
import io.multy.model.entities.wallet.WalletAddress;
import io.multy.model.requests.HdTransactionRequestEntity;
import io.multy.storage.RealmManager;
import io.multy.ui.MyWebView;
import io.multy.ui.adapters.SpinnerWalletsAdapter;
import io.multy.ui.fragments.dialogs.WalletChooserDialogFragment;
import io.multy.ui.fragments.send.SendSummaryFragment;
import io.multy.util.Constants;
import io.multy.util.JniException;
import io.multy.util.NativeDataHelper;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import timber.log.Timber;
import trust.core.entity.Address;

public class Web3Fragment extends BaseFragment {

    @BindView(R.id.web_view)
    MyWebView webView;
    @BindView(R.id.input_address)
    EditText inputAddress;
    @BindView(R.id.spinner)
    NiceSpinner spinner;

    private Wallet selectedWallet;
    private long walletId;
    byte[] seed;

    public static Web3Fragment newInstance() {
        Web3Fragment web3Fragment = new Web3Fragment();
        return web3Fragment;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View convertView = inflater.inflate(R.layout.fragment_web_dapps, container, false);
        ButterKnife.bind(this, convertView);

        seed = RealmManager.getSettingsDao().getSeed().getSeed();
        showChooser();
        inputAddress.setOnEditorActionListener((v, actionId, event) -> {
            if ((actionId == EditorInfo.IME_ACTION_GO)) {
                webView.loadUrl(inputAddress.getText().toString());
                return true;

            }
            return false;
        });

        initSpinner();
        return convertView;
    }

    private void initSpinner() {
        List<Wallet> wallets = RealmManager.getAssetsDao().getAvailableWallets(NativeDataHelper.Blockchain.ETH.getValue(), NativeDataHelper.NetworkId.ETH_MAIN_NET.getValue());
        if (wallets.size() > 0) {
            spinner.setAdapter(new SpinnerWalletsAdapter(getActivity(), R.layout.item_spinner_wallet, wallets.toArray(new Wallet[]{})));
        } else {
            spinner.setAdapter(null);
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (resultCode == Activity.RESULT_OK && data != null) {
            getActivity().getIntent().putExtra(Constants.EXTRA_WALLET_ID, data.getLongExtra(Constants.EXTRA_WALLET_ID, 0));
            selectedWallet = RealmManager.getAssetsDao().getWalletById(data.getLongExtra(Constants.EXTRA_WALLET_ID, 0));
            walletId = selectedWallet.getId();

            final WalletAddress walletAddress = selectedWallet.getActiveAddress();
            if (walletAddress.getAmount() > 5000) {
                initWebView(walletAddress.getAddress());
            } else {
                Toast.makeText(getActivity(), "Wrong wallet. Please choose another one", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void showChooser() {
        if (getFragmentManager() != null) {
            WalletChooserDialogFragment dialog = WalletChooserDialogFragment.getInstance(NativeDataHelper.Blockchain.ETH.getValue());
            dialog.exceptMultisig(true);
            dialog.setMainNet(true);
            dialog.setTargetFragment(this, WalletChooserDialogFragment.REQUEST_WALLET_ID);
            dialog.show(getFragmentManager(), WalletChooserDialogFragment.TAG);
        }
    }

    private void initWebView(String address) {
//        https://plasma.bankex.com/
//        "https://dapps.trustwalletapp.com/"
//        webView.setChainId(4);
//        webView.setRpcUrl("https://testnet.infura.io/llyrtzQ3YhkdESt2Fzrk");
        final String url = "https://dapps.trustwalletapp.com/";
        webView.loadUrl(url);
        inputAddress.setText(url);
        webView.requestFocus();
        webView.setWalletAddress(new Address(address));
        webView.setWebViewClient(new WebViewClient() {

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                inputAddress.setText(request.getUrl().toString());
                return super.shouldOverrideUrlLoading(view, request);
            }
        });

        webView.setOnSignMessageListener(message -> {
            Toast.makeText(getActivity(), "Message: " + message.value, Toast.LENGTH_LONG).show();
            Timber.d("onSignMessage:" + message.value);
            webView.onSignCancel(message);
        });
        webView.setOnSignPersonalMessageListener(message -> {
            Toast.makeText(getActivity(), "Personal message: " + message.value, Toast.LENGTH_LONG).show();
            Timber.d("onSignPersonalMessage:" + message.value + "\n");
            webView.onSignCancel(message);
        });
        webView.setOnSignTransactionListener(transaction -> {
            getActivity().runOnUiThread(() -> {
                try {
                    Timber.i("payload " + transaction.payload);
                    Timber.i("start signing tx");

                    if (selectedWallet == null || !selectedWallet.isValid()) {
                        selectedWallet = RealmManager.getAssetsDao().getWalletById(walletId);
                    }

                    final byte[] tx = NativeDataHelper.makeTransactionEthPayload(
                            seed, selectedWallet.getIndex(), selectedWallet.getActiveAddress().getIndex(),
                            selectedWallet.getCurrencyId(), selectedWallet.getNetworkId(), selectedWallet.getBalance(), transaction.value.toString(),
                            transaction.recipient.toString(), String.valueOf(transaction.gasLimit), transaction.gasPrice.toString(), selectedWallet.getEthWallet().getNonce(), transaction.payload.replace("0x", ""));
                    Timber.i("start converting to hex");
                    final String hex = "0x" + SendSummaryFragment.byteArrayToHex(tx);
                    Timber.i("hex converted " + hex);

                    final HdTransactionRequestEntity entity = new HdTransactionRequestEntity(selectedWallet.getCurrencyId(), selectedWallet.getNetworkId(),
                            new HdTransactionRequestEntity.Payload("", selectedWallet.getActiveAddress().getIndex(),
                                    selectedWallet.getIndex(), hex, false));

                    Timber.i("hex=%s", hex);
                    MultyApi.INSTANCE.sendHdTransaction(entity).enqueue(new Callback<ResponseBody>() {
                        @Override
                        public void onResponse(@NonNull Call<ResponseBody> call, @NonNull Response<ResponseBody> response) {
                            if (response.isSuccessful()) {
                                Toast.makeText(getActivity(), "Buy item result - SUCCESS", Toast.LENGTH_LONG).show();
                                Timber.i("BUYING SUCCESS");
                                webView.onSignTransactionSuccessful(transaction, hex);
                            } else {
                                Timber.i("BUYING FAIL");
                                webView.onSignError(transaction, "Something went wrong");
                            }
                        }

                        @Override
                        public void onFailure(@NonNull Call<ResponseBody> call, @NonNull Throwable t) {
                            t.printStackTrace();
                            Timber.i("BUYING FAIL");
                        }
                    });
                } catch (JniException e) {
                    e.printStackTrace();
                }
            });

            Toast.makeText(getActivity(), "Transaction: " + transaction.value, Toast.LENGTH_LONG).show();
            Timber.d("onSignTransactionMessage:" + transaction.value + "\n recepient:" + transaction.recipient + "\n payLoad:" + transaction.payload);
//            webView.onSignCancel(transaction);
        });
    }

    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            getActivity().finish();
        }
    }
}
