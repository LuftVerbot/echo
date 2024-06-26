package dev.brahmkshatriya.echo.ui.settings

import android.content.Context
import android.os.Bundle
import android.view.View
import androidx.appcompat.content.res.AppCompatResources
import androidx.fragment.app.activityViewModels
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceViewHolder
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.ui.common.openFragment
import dev.brahmkshatriya.echo.viewmodels.ExtensionViewModel


class SettingsFragment : BaseSettingsFragment() {
    override val title get() = getString(R.string.settings)
    override val transitionName = "settings"
    override val creator = { SettingsPreference() }

    class SettingsPreference : PreferenceFragmentCompat() {

        private val extensionViewModel: ExtensionViewModel by activityViewModels()
        private val extension get() = extensionViewModel.currentExtension

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            val context = preferenceManager.context
            preferenceManager.sharedPreferencesName = context.packageName
            preferenceManager.sharedPreferencesMode = Context.MODE_PRIVATE
            val screen = preferenceManager.createPreferenceScreen(context)
            preferenceScreen = screen

            fun Preference.add(block: Preference.() -> Unit = {}) {
                block()
                layoutResource = R.layout.preference
                screen.addPreference(this)
            }

            TransitionPreference(context).add {
                title = getString(R.string.look_and_feel)
                key = "look"
                summary = getString(R.string.look_and_feel_summary)
                icon = AppCompatResources.getDrawable(context, R.drawable.ic_palette)
            }

            TransitionPreference(context).add {
                title = getString(R.string.audio)
                key = "audio"
                summary = getString(R.string.audio_summary)
                icon = AppCompatResources.getDrawable(context, R.drawable.ic_queue_music)
            }

            TransitionPreference(context, extension?.metadata?.id).add {
                title = getString(R.string.extension_settings)
                key = "extension"
                summary = getString(R.string.extension_settings_summary)
                icon = AppCompatResources.getDrawable(context, R.drawable.ic_stream)
            }

            TransitionPreference(context).add {
                title = getString(R.string.about)
                key = "about"
                summary = getString(R.string.about_summary)
                icon = AppCompatResources.getDrawable(context, R.drawable.ic_info)
            }
        }

        class TransitionPreference(
            context: Context,
            private val transition: String? = null
        ) : Preference(context) {
            override fun onBindViewHolder(holder: PreferenceViewHolder) {
                super.onBindViewHolder(holder)
                holder.itemView.id = key.hashCode()
                holder.itemView.transitionName = transition ?: key
            }
        }

        override fun onPreferenceTreeClick(preference: Preference): Boolean {
            val fragment = when (preference.key) {
                "about" -> AboutFragment()
                "audio" -> AudioFragment()
                "extension" -> ExtensionFragment.newInstance(extension ?: return false)
                "look" -> LookFragment()
                else -> return false
            }

            val view = listView.findViewById<View>(preference.key.hashCode())
            parentFragment?.openFragment(fragment, view)
            return true
        }
    }

}

