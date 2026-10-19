/^fun MiniMap\(gps: GpsSpeed, modifier: Modifier\) \{$/ {
  print "fun MiniMap(gps: GpsSpeed, nav: Navigation, onOpenDestinations: () -> Unit, modifier: Modifier) {"; next }
/^    var heading by remember \{ mutableFloatStateOf\(0f\) \}$/ {
  print
  print "    val active by nav.active.collectAsStateWithLifecycle()"
  print "    // Route in tile units, converted once per new route."
  print "    val routeTiles = remember(active?.route) {"
  print "        active?.route?.let { r ->"
  print "            val xs = DoubleArray(r.lats.size); val ys = DoubleArray(r.lats.size)"
  print "            for (i in xs.indices) { val (x, y) = RoadTiles.tileXY(r.lats[i], r.lons[i]); xs[i] = x; ys[i] = y }"
  print "            xs to ys"
  print "        }"
  print "    }"
  next }
/clickable\(enabled = !permitted\)/ {
  sub(/clickable\(enabled = !permitted\) \{ askLocation.launch\(locationPerms\) \}/, "clickable { if (permitted) onOpenDestinations() else askLocation.launch(locationPerms) }"); print; next }
/^                    \/\/ Local streets first, main roads on top.$/ { inroads=1 }
inroads && /^                    \}$/ {
  print
  print "                    // The route ahead of the car, on top of the streets."
  print "                    routeTiles?.let { (xs, ys) ->"
  print "                        val from = (active?.segment ?: 0).coerceIn(0, xs.size - 2)"
  print "                        val path = Path()"
  print "                        for (i in from until xs.size) {"
  print "                            val px = (xs[i] - tx).toFloat(); val py = (ys[i] - ty).toFloat()"
  print "                            if (i == from) path.moveTo(px, py) else path.lineTo(px, py)"
  print "                        }"
  print "                        drawPath(path, routeColor, style = Stroke(7.dp.toPx() / scale, cap = StrokeCap.Round, join = StrokeJoin.Round))"
  print "                    }"
  inroads=0; next }
/© OpenStreetMap/ {
  print "        // Only text on the map while navigating: distance left to the destination."
  print "        active?.remainingM?.let { m ->"
  print "            Text("
  print "                formatDistance(m), color = Palette.text, fontSize = 15.sp, fontWeight = FontWeight.Bold,"
  print "                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp)"
  print "                    .clip(RoundedCornerShape(8.dp)).background(Palette.background.copy(alpha = 0.75f)).padding(horizontal = 10.dp, vertical = 3.dp),"
  print "            )"
  print "        }"
  next }
{ print }
