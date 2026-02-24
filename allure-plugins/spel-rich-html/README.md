# spel-rich-html — Allure Plugin

Full-height HTML attachment viewer for spel HTTP exchanges.

## What it does

- Renders spel's "HTTP Exchange" HTML attachments at full height (500px default, auto-resizes)
- Adds expand button to view at 90% viewport height
- Adds "open in new tab" button for standalone viewing
- Falls back to default 150px iframe for non-spel HTML attachments

## Installation

### Option 1: Copy to Allure plugins directory

```bash
# Find your Allure installation
ALLURE_HOME=$(dirname $(dirname $(which allure)))

# Copy plugin
cp -r allure-plugins/spel-rich-html $ALLURE_HOME/plugins/

# Enable it
echo "spel-rich-html" >> $ALLURE_HOME/config/plugins.yml
```

### Option 2: Package as ZIP

```bash
cd allure-plugins/spel-rich-html
zip -r spel-rich-html.zip allure-plugin.yml static/
# Unzip into $ALLURE_HOME/plugins/
```

### Option 3: Allure Docker Service

Mount the plugin into the container:

```yaml
volumes:
  - ./allure-plugins/spel-rich-html:/opt/allure/plugins/spel-rich-html
```

## How it works

1. Plugin registers a custom viewer for `text/html` attachments
2. Checks attachment name — spel uses "HTTP Exchange", "Spel*", "API Response*"
3. For spel attachments: renders in a styled iframe with toolbar
4. For other HTML: uses default Allure 150px iframe

The spel HTML content includes a postMessage script that sends its height to the parent, enabling auto-resize when collapsible sections are toggled.

## Attachment name patterns

Attachments matching these patterns get the rich viewer:
- `HTTP Exchange`
- `Spel*`
- `API Response*`

Or use the custom MIME type: `application/vnd.spel.rich-html`

## Files

```
spel-rich-html/
├── allure-plugin.yml    # Plugin definition
└── static/
    ├── index.js         # Backbone.Marionette viewer
    └── styles.css       # Toolbar and iframe styles
```
