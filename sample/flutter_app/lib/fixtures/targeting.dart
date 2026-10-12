import 'package:flutter/material.dart';

import 'common.dart';

/// How a Flutter screen looks to Spock's element tools and accessibility audit (#182).
///
/// Flutter publishes a widget's words as the Android content-desc, not as its text, so an agent
/// searching for the words it sees has to match both. The screen has one of each shape that tripped
/// an agent on a real app: an icon-only tab whose only name is a `Semantics(label:)`, two buttons
/// with the same label, a small badge inside a tappable card, an offer banner that does not scroll
/// by touch, a shrink-wrapped `IconButton`, and a button found by its `Semantics(identifier:)`.
class TargetingScreen extends StatefulWidget {
  const TargetingScreen({super.key});

  @override
  State<TargetingScreen> createState() => _TargetingScreenState();
}

class _TargetingScreenState extends State<TargetingScreen> {
  final _offers = PageController(viewportFraction: 0.85, initialPage: 1);
  int _tab = 0;
  String _result = 'Nothing tapped yet';

  @override
  void dispose() {
    _offers.dispose();
    super.dispose();
  }

  void _say(String what) => setState(() => _result = what);

  void _select(int tab, String label) {
    setState(() => _tab = tab);
    _say('Tab $label selected');
  }

  @override
  Widget build(BuildContext context) {
    return FixtureScaffold(
      title: 'Element targeting',
      // The note names no label below: a substring search for one would find the note as well.
      note: 'Flutter publishes every label below as content-desc. The sample README lists what each '
          'element tool and the accessibility audit should answer here.',
      children: [
        Row(
          children: [
            _IconTab(label: 'Start', icon: Icons.home, selected: _tab == 0, onTap: () => _select(0, 'Start')),
            _IconTab(label: 'Suche', icon: Icons.search, selected: _tab == 1, onTap: () => _select(1, 'Suche')),
            _IconTab(label: 'Profil', icon: Icons.person, selected: _tab == 2, onTap: () => _select(2, 'Profil')),
          ],
        ),
        const SizedBox(height: 12),
        Row(
          children: [
            OutlinedButton(onPressed: () => _say('Details of plan A'), child: const Text('Details')),
            const SizedBox(width: 12),
            OutlinedButton(onPressed: () => _say('Details of plan B'), child: const Text('Details')),
          ],
        ),
        const SizedBox(height: 12),
        _PromoCard(tariff: 'S', onTap: () => _say('Tarif S opened')),
        const SizedBox(height: 12),
        // An auto-advancing offer banner does not scroll by touch, so Flutter publishes no scroll
        // action for it, yet it still cuts the neighbouring offers' bounds at its edge. The timer
        // that would advance it is left out, so the screen holds still for a dump.
        SizedBox(
          height: 150,
          child: PageView(
            controller: _offers,
            physics: const NeverScrollableScrollPhysics(),
            children: [
              for (final tariff in ['M', 'L', 'XL'])
                _PromoCard(tariff: tariff, onTap: () => _say('Tarif $tariff opened')),
            ],
          ),
        ),
        const SizedBox(height: 12),
        Row(
          children: [
            const Text('Roaming included'),
            IconButton(
              icon: const Icon(Icons.info_outline, size: 18),
              tooltip: 'Roaming info',
              padding: EdgeInsets.zero,
              constraints: const BoxConstraints(),
              style: IconButton.styleFrom(tapTargetSize: MaterialTapTargetSize.shrinkWrap),
              onPressed: () => _say('Roaming info opened'),
            ),
          ],
        ),
        IdButton(id: 'targeting_save', label: 'Speichern', onPressed: () => _say('Saved')),
        ResultText(_result),
      ],
    );
  }
}

/// An icon with no words of its own: its only name is the `Semantics(label:)` around it, which
/// Flutter publishes as content-desc.
class _IconTab extends StatelessWidget {
  const _IconTab({required this.label, required this.icon, required this.selected, required this.onTap});

  final String label;
  final IconData icon;
  final bool selected;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return Semantics(
      label: label,
      button: true,
      selected: selected,
      child: InkWell(
        onTap: onTap,
        child: SizedBox(
          width: 72,
          height: 56,
          child: Icon(icon, color: selected ? Theme.of(context).colorScheme.primary : null),
        ),
      ),
    );
  }
}

/// A tappable offer card with a small badge in its corner. Only the card can be tapped: the badge's
/// words become the first line of the card's content-desc, and the badge is no node of its own.
class _PromoCard extends StatelessWidget {
  const _PromoCard({required this.tariff, required this.onTap});

  final String tariff;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Card(
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        onTap: onTap,
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Container(
                padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
                decoration: BoxDecoration(color: scheme.tertiary, borderRadius: BorderRadius.circular(4)),
                child: Text('GRATIS E-SIM', style: TextStyle(color: scheme.onTertiary, fontSize: 11)),
              ),
              const SizedBox(height: 8),
              Text('Tarif $tariff'),
            ],
          ),
        ),
      ),
    );
  }
}
