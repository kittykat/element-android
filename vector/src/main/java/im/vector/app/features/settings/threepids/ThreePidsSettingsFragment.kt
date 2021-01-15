/*
 * Copyright (c) 2020 New Vector Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package im.vector.app.features.settings.threepids

import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.airbnb.mvrx.fragmentViewModel
import com.airbnb.mvrx.withState
import im.vector.app.R
import im.vector.app.core.dialogs.PromptPasswordDialog
import im.vector.app.core.dialogs.withColoredButton
import im.vector.app.core.extensions.cleanup
import im.vector.app.core.extensions.configureWith
import im.vector.app.core.extensions.exhaustive
import im.vector.app.core.extensions.getFormattedValue
import im.vector.app.core.extensions.hideKeyboard
import im.vector.app.core.extensions.isEmail
import im.vector.app.core.extensions.isMsisdn
import im.vector.app.core.platform.OnBackPressed
import im.vector.app.core.platform.VectorBaseFragment
import im.vector.app.databinding.FragmentGenericRecyclerBinding

import org.matrix.android.sdk.api.session.identity.ThreePid
import javax.inject.Inject

class ThreePidsSettingsFragment @Inject constructor(
        private val viewModelFactory: ThreePidsSettingsViewModel.Factory,
        private val epoxyController: ThreePidsSettingsController
) :
        VectorBaseFragment<FragmentGenericRecyclerBinding>(),
        OnBackPressed,
        ThreePidsSettingsViewModel.Factory by viewModelFactory,
        ThreePidsSettingsController.InteractionListener {

    private val viewModel: ThreePidsSettingsViewModel by fragmentViewModel()

    override fun getBinding(inflater: LayoutInflater, container: ViewGroup?): FragmentGenericRecyclerBinding {
        return FragmentGenericRecyclerBinding.inflate(inflater, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        views.genericRecyclerView.configureWith(epoxyController)
        epoxyController.interactionListener = this

        viewModel.observeViewEvents {
            when (it) {
                is ThreePidsSettingsViewEvents.Failure      -> displayErrorDialog(it.throwable)
                ThreePidsSettingsViewEvents.RequestPassword -> askUserPassword()
            }.exhaustive
        }
    }

    private fun askUserPassword() {
        PromptPasswordDialog().show(requireActivity()) { password ->
            viewModel.handle(ThreePidsSettingsAction.AccountPassword(password))
        }
    }

    override fun onDestroyView() {
        views.genericRecyclerView.cleanup()
        epoxyController.interactionListener = null
        super.onDestroyView()
    }

    override fun onResume() {
        super.onResume()
        (activity as? AppCompatActivity)?.supportActionBar?.setTitle(R.string.settings_emails_and_phone_numbers_title)
    }

    override fun invalidate() = withState(viewModel) { state ->
        if (state.isLoading) {
            showLoadingDialog()
        } else {
            dismissLoadingDialog()
        }
        epoxyController.setData(state)
    }

    override fun addEmail() {
        viewModel.handle(ThreePidsSettingsAction.ChangeUiState(ThreePidsSettingsUiState.AddingEmail(null)))
    }

    override fun doAddEmail(email: String) {
        // Sanity
        val safeEmail = email.trim().replace(" ", "")
        viewModel.handle(ThreePidsSettingsAction.ChangeUiState(ThreePidsSettingsUiState.AddingEmail(null)))

        // Check that email is valid
        if (!safeEmail.isEmail()) {
            viewModel.handle(ThreePidsSettingsAction.ChangeUiState(ThreePidsSettingsUiState.AddingEmail(getString(R.string.auth_invalid_email))))
            return
        }

        viewModel.handle(ThreePidsSettingsAction.AddThreePid(ThreePid.Email(safeEmail)))
    }

    override fun addMsisdn() {
        viewModel.handle(ThreePidsSettingsAction.ChangeUiState(ThreePidsSettingsUiState.AddingPhoneNumber(null)))
    }

    override fun doAddMsisdn(msisdn: String) {
        // Sanity
        val safeMsisdn = msisdn.trim().replace(" ", "")

        viewModel.handle(ThreePidsSettingsAction.ChangeUiState(ThreePidsSettingsUiState.AddingPhoneNumber(null)))

        // Check that phone number is valid
        if (!msisdn.startsWith("+")) {
            viewModel.handle(
                    ThreePidsSettingsAction.ChangeUiState(ThreePidsSettingsUiState.AddingPhoneNumber(getString(R.string.login_msisdn_error_not_international)))
            )
            return
        }

        if (!msisdn.isMsisdn()) {
            viewModel.handle(
                    ThreePidsSettingsAction.ChangeUiState(ThreePidsSettingsUiState.AddingPhoneNumber(getString(R.string.login_msisdn_error_other)))
            )
            return
        }

        viewModel.handle(ThreePidsSettingsAction.AddThreePid(ThreePid.Msisdn(safeMsisdn)))
    }

    override fun submitCode(threePid: ThreePid.Msisdn, code: String) {
        viewModel.handle(ThreePidsSettingsAction.SubmitCode(threePid, code))
        // Hide the keyboard
        view?.hideKeyboard()
    }

    override fun cancelAdding() {
        viewModel.handle(ThreePidsSettingsAction.ChangeUiState(ThreePidsSettingsUiState.Idle))
        // Hide the keyboard
        view?.hideKeyboard()
    }

    override fun continueThreePid(threePid: ThreePid) {
        viewModel.handle(ThreePidsSettingsAction.ContinueThreePid(threePid))
    }

    override fun cancelThreePid(threePid: ThreePid) {
        viewModel.handle(ThreePidsSettingsAction.CancelThreePid(threePid))
    }

    override fun deleteThreePid(threePid: ThreePid) {
        AlertDialog.Builder(requireActivity())
                .setMessage(getString(R.string.settings_remove_three_pid_confirmation_content, threePid.getFormattedValue()))
                .setPositiveButton(R.string.remove) { _, _ ->
                    viewModel.handle(ThreePidsSettingsAction.DeleteThreePid(threePid))
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
                .withColoredButton(DialogInterface.BUTTON_POSITIVE)
    }

    override fun onBackPressed(toolbarButton: Boolean): Boolean {
        return withState(viewModel) {
            if (it.uiState is ThreePidsSettingsUiState.Idle) {
                false
            } else {
                cancelAdding()
                true
            }
        }
    }
}