import 'package:flutter_test/flutter_test.dart';
import 'package:autocall_record/main.dart';

void main() {
  testWidgets('AutoCall app initial smoke test', (WidgetTester tester) async {
    await tester.pumpWidget(const AutoCallApp());
    expect(find.text('AutoCall'), findsOneWidget);
  });
}
