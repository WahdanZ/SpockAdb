import 'package:flutter/material.dart';

import 'common.dart';

/// List → detail routes. Deep links open details directly:
/// `spockflutter://open/item/42?ref=spock` → `/item/42`.
class ItemListScreen extends StatelessWidget {
  const ItemListScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Items')),
      body: ListView.builder(
        itemCount: 50,
        itemBuilder: (context, index) => Semantics(
          identifier: 'item_$index',
          child: ListTile(
            title: Text('Item $index'),
            onTap: () => Navigator.of(context).pushNamed('/item/$index'),
          ),
        ),
      ),
    );
  }
}

class ItemDetailScreen extends StatelessWidget {
  const ItemDetailScreen({super.key, required this.id, this.ref});

  final String id;
  final String? ref;

  @override
  Widget build(BuildContext context) {
    return FixtureScaffold(
      title: 'Item $id',
      note: 'Route /item/$id. Opened from a deep link when ref is set.',
      children: [
        ResultText('id=$id ref=${ref ?? '-'}'),
        IdButton(
          id: 'detail_next',
          label: 'Open item ${int.tryParse(id) == null ? 1 : int.parse(id) + 1}',
          onPressed: () => Navigator.of(context).pushNamed('/item/${(int.tryParse(id) ?? 0) + 1}'),
        ),
      ],
    );
  }
}
