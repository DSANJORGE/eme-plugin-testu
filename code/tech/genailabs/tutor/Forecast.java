package tech.genailabs.tutor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Linear trend of a daily share (0..1) and when it reaches a target. Server-owned so every console reads one answer.
 * Fit = least squares over the last FIT days; band = residual spread widening with sqrt(days ahead).
 * ponytail: linear only; learning curves saturate, so a logistic fit is the upgrade once a pilot has 6+ weeks of data.
 */
public final class Forecast
{
	public static final int FIT = 14, MIN_DAYS = 7, HORIZON = 90;

	private Forecast()
	{
	}

	/**
	 * history = one share per day, oldest first, last = today. Returns {current, slope (per day), status, eta, etaEarly,
	 * etaLate (days from today, null when beyond HORIZON), projection [[daysAhead, value, low, high]]}. status: reached |
	 * onpace | notonpace | insufficient.
	 */
	public static Map<String, Object> of(List<Double> history, double target)
	{
		Map<String, Object> out = new LinkedHashMap<>();
		int n = history.size();
		double current = n == 0 ? 0 : history.get(n - 1);
		out.put("current", current);
		if (n > 0 && current >= target)
		{
			out.put("status", "reached");
			return out;
		}
		if (n < MIN_DAYS)
		{
			out.put("status", "insufficient");
			return out;
		}
		int k = Math.min(FIT, n);
		double sx = 0, sy = 0, sxx = 0, sxy = 0;
		for (int i = 0; i < k; i++)
		{
			double x = i - (k - 1), y = history.get(n - k + i); // x = 0 today
			sx += x;
			sy += y;
			sxx += x * x;
			sxy += x * y;
		}
		double slope = (k * sxy - sx * sy) / (k * sxx - sx * sx);
		double icept = (sy - slope * sx) / k;
		double sse = 0;
		for (int i = 0; i < k; i++)
		{
			double r = history.get(n - k + i) - (icept + slope * (i - (k - 1)));
			sse += r * r;
		}
		double sd = Math.max(Math.sqrt(sse / Math.max(1, k - 2)), 0.01);
		out.put("slope", slope);
		List<double[]> projection = new ArrayList<>();
		Integer eta = null, early = null, late = null;
		for (int h = 0; h <= HORIZON; h++)
		{
			double v = clamp(icept + slope * h), w = sd * Math.sqrt(Math.max(h, 1));
			double lo = clamp(v - w), hi = clamp(v + w);
			projection.add(new double[] {h, v, h == 0 ? v : lo, h == 0 ? v : hi});
			if (eta == null && v >= target)
				eta = h;
			if (early == null && hi >= target)
				early = h;
			if (late == null && lo >= target)
				late = h;
			if (late != null && h >= late + 7)
				break; // the chart only needs a week past the latest crossing
		}
		out.put("projection", projection);
		out.put("eta", eta);
		out.put("etaEarly", early);
		out.put("etaLate", late);
		out.put("status", slope > 0.0005 && eta != null ? "onpace" : "notonpace");
		return out;
	}

	private static double clamp(double v)
	{
		return Math.max(0, Math.min(1, v));
	}

	/** Self-check: java -cp build tech.genailabs.tutor.Forecast */
	public static void main(String[] args)
	{
		List<Double> rising = new ArrayList<>();
		for (int i = 0; i < 20; i++)
			rising.add(0.1 + 0.02 * i); // today 0.48, +2 pt/day -> 0.8 in 16 days
		Map<String, Object> f = of(rising, 0.8);
		check("onpace".equals(f.get("status")), "rising is on pace: " + f);
		check(Math.abs((Integer) f.get("eta") - 16) <= 1, "eta ~16: " + f.get("eta"));
		check(of(List.of(0.1, 0.1, 0.1), 0.8).get("status").equals("insufficient"), "short history");
		check(of(List.of(0.9), 0.8).get("status").equals("reached"), "reached");
		List<Double> flat = new ArrayList<>();
		for (int i = 0; i < 10; i++)
			flat.add(0.3);
		check(of(flat, 0.8).get("status").equals("notonpace"), "flat");
		System.out.println("Forecast ok");
	}

	private static void check(boolean ok, String what)
	{
		if (!ok)
			throw new AssertionError(what);
	}
}
