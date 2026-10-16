// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** A separate test-APK process cannot borrow the target APK's Kotlin runtime. */
public final class ScreenActionFixtureActivity extends Activity implements View.OnClickListener, View.OnLongClickListener {
    private int clicks;
    private int longs;
    private TextView result;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(24, 100, 24, 60);
        TextView title = new TextView(this);
        title.setText("screen-action-fixture");
        layout.addView(title);
        Button button = new Button(this);
        button.setText("Tap target");
        button.setContentDescription("tap-target");
        button.setOnClickListener(this);
        button.setOnLongClickListener(this);
        layout.addView(button);
        result = new TextView(this);
        result.setText("clicks=0 longs=0");
        result.setContentDescription("action-result");
        layout.addView(result);
        EditText input = new EditText(this);
        input.setContentDescription("edit-target");
        input.setSingleLine();
        input.setText("initial");
        layout.addView(input);
        EditText password = new EditText(this);
        password.setContentDescription("password-target");
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        password.setText("password-must-not-be-targetable");
        layout.addView(password);
        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < 30; i++) {
            TextView row = new TextView(this);
            row.setText("scroll-row-" + i);
            row.setMinHeight(100);
            rows.addView(row);
        }
        ScrollView scroll = new ScrollView(this);
        scroll.setContentDescription("scroll-target");
        scroll.addView(rows);
        layout.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 300));
        setContentView(layout);
    }

    @Override public void onClick(View view) {
        clicks++;
        updateResult();
    }

    @Override public boolean onLongClick(View view) {
        longs++;
        updateResult();
        return true;
    }

    private void updateResult() { result.setText("clicks=" + clicks + " longs=" + longs); }
}
