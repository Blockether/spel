(function () {
  'use strict';

  var DEFAULT_HEIGHT = '500px';
  var MAX_HEIGHT = '90vh';
  var SPEL_ATTACHMENT_PATTERN = /^(HTTP Exchange|Spel|API Response)/i;

  function richHtmlTemplate(sourceUrl, name) {
    return '<div class="spel-rich-html__container">' +
      '<div class="spel-rich-html__wrapper">' +
        '<div class="spel-rich-html__toolbar">' +
          '<button class="spel-rich-html__btn spel-rich-html__btn--expand" title="Expand">&#x2922;</button>' +
          '<button class="spel-rich-html__btn spel-rich-html__btn--newtab" title="Open in new tab">&#x21D7;</button>' +
          '<span class="spel-rich-html__label">' + (name || 'HTML') + '</span>' +
        '</div>' +
        '<div class="spel-rich-html__frame-wrap">' +
          '<iframe class="spel-rich-html__frame" src="' + sourceUrl + '" sandbox="allow-scripts allow-same-origin"></iframe>' +
        '</div>' +
      '</div>' +
    '</div>';
  }

  function setupIframe(iframe) {
    window.addEventListener('message', function (e) {
      if (e.data && typeof e.data.spelHtmlHeight === 'number') {
        iframe.style.height = Math.min(e.data.spelHtmlHeight, window.innerHeight * 0.9) + 'px';
      }
    });
    iframe.addEventListener('load', function () {
      setTimeout(function () {
        if (!iframe.style.height || iframe.style.height === '150px') {
          iframe.style.height = DEFAULT_HEIGHT;
        }
      }, 100);
    });
  }

  function toggleExpand(view) {
    var wrap = view.$('.spel-rich-html__frame-wrap')[0];
    var iframe = view.$('.spel-rich-html__frame')[0];
    if (wrap.classList.contains('spel-rich-html__frame-wrap--expanded')) {
      wrap.classList.remove('spel-rich-html__frame-wrap--expanded');
      iframe.style.height = DEFAULT_HEIGHT;
    } else {
      wrap.classList.add('spel-rich-html__frame-wrap--expanded');
      iframe.style.height = MAX_HEIGHT;
    }
  }

  function openNewTab(view) {
    window.open(view.$('.spel-rich-html__frame').attr('src'), '_blank');
  }

  var RichHtmlView = Backbone.Marionette.View.extend({
    className: 'spel-rich-html__container',
    template: function (d) { return richHtmlTemplate(d.sourceUrl, d.attachmentName); },
    templateContext: function () { return { sourceUrl: this.options.sourceUrl, attachmentName: this.options.attachmentName || 'HTML' }; },
    events: {
      'click .spel-rich-html__btn--expand': function () { toggleExpand(this); },
      'click .spel-rich-html__btn--newtab': function () { openNewTab(this); }
    },
    onRender: function () { setupIframe(this.$('.spel-rich-html__frame')[0]); }
  });

  allure.api.addAttachmentViewer('application/vnd.spel.rich-html', { View: RichHtmlView, icon: 'fa fa-code' });

  allure.api.addAttachmentViewer('text/html', {
    View: Backbone.Marionette.View.extend({
      className: 'spel-html-viewer',
      initialize: function (o) { this.options = o; this.attachmentName = o.attachmentName || ''; },
      template: function (d) {
        return SPEL_ATTACHMENT_PATTERN.test(d.attachmentName)
          ? richHtmlTemplate(d.sourceUrl, d.attachmentName)
          : '<iframe class="attachment__iframe" src="' + d.sourceUrl + '"></iframe>';
      },
      templateContext: function () { return { sourceUrl: this.options.sourceUrl, attachmentName: this.attachmentName }; },
      events: {
        'click .spel-rich-html__btn--expand': function () { toggleExpand(this); },
        'click .spel-rich-html__btn--newtab': function () { openNewTab(this); }
      },
      onRender: function () {
        var iframe = this.$('.spel-rich-html__frame')[0];
        if (iframe) { setupIframe(iframe); }
      }
    }),
    icon: 'fa fa-file-code-o'
  });

})();
