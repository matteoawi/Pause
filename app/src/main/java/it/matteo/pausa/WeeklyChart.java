package it.matteo.pausa;

import android.content.Context;
import android.graphics.*;
import android.view.View;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Seven measured daily buckets, no illustrative or projected values. */
final class WeeklyChart extends View {
    private long[] values = new long[7];
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private LocalDate renderedDay;
    WeeklyChart(Context context) { super(context); density = getResources().getDisplayMetrics().density; setValues(new long[7]); }
    void setValues(long[] next) {
        LocalDate now = LocalDate.now();
        if (Arrays.equals(values, next) && now.equals(renderedDay) && getContentDescription() != null) return;
        renderedDay = now; values = next.clone(); StringBuilder description = new StringBuilder();
        for (int i = 0; i < 7; i++) description.append(values[i] < 0
            ? getContext().getString(R.string.chart_no_data, now.minusDays(6 - i).toString())
            : getContext().getString(R.string.chart_day, now.minusDays(6 - i).toString(), java.text.NumberFormat.getIntegerInstance(AppPreferences.locale(getContext())).format(values[i]))).append(". ");
        setContentDescription(description); invalidate();
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas); float column = getWidth() / 7f, available = getHeight() - 24 * density;
        long largest = 1; for (long value : values) largest = Math.max(largest, value);
        Locale locale = AppPreferences.locale(getContext()); DateTimeFormatter format = DateTimeFormatter.ofPattern("EEE", locale);
        for (int i = 0; i < 7; i++) {
            float height = values[i] <= 0 ? 2 * density : Math.max(4 * density, available * values[i] / largest);
            paint.setColor(getContext().getColor(values[i] <= 0 ? R.color.pause_line : i == 6 ? R.color.pause_primary : R.color.pause_history));
            float left = column * i + column * .25f, right = column * (i + 1) - column * .25f;
            canvas.drawRoundRect(left, available - height, right, available, 3 * density, 3 * density, paint);
            paint.setColor(getContext().getColor(R.color.pause_muted)); paint.setTextSize(11 * getResources().getDisplayMetrics().scaledDensity); paint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(format.format(LocalDate.now().minusDays(6 - i)).toUpperCase(locale), column * (i + .5f), getHeight() - 4 * density, paint);
        }
    }
}
