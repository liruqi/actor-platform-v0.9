package im.actor.sdk.controllers.fragment.group;

import android.os.Bundle;

import im.actor.messenger.R;
import im.actor.messenger.app.Intents;
import im.actor.sdk.controllers.activity.BaseFragmentActivity;

/**
 * Created by korka on 25.05.15.
 */
public class IntegrationTokenActivity extends BaseFragmentActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getSupportActionBar().setTitle(R.string.integration_token_title);

        int chatId = getIntent().getIntExtra(Intents.EXTRA_GROUP_ID, 0);

        if (savedInstanceState == null) {
            showFragment(IntegrationTokenFragment.create(chatId), false, false);
        }
    }

}
