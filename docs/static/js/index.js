// Project page behavior. Adapted from the Academic Project Page Template's index.js (BibTeX copy, scroll-to-top,
// carousel, video autoplay); the template's jQuery and "More Works" parts are not used here.

function copyBibTeX() {
  copyText(document.getElementById('bibtex-code'), document.querySelector('.copy-bibtex-btn'));
}

/** A Quick start command: the code block next to the button. */
function copyCommand(button) {
  copyText(button.parentElement.querySelector('code'), button);
}

function copyText(code, button) {
  if (!code || !button) return;
  const label = button.querySelector('.copy-text');
  const done = () => {
    button.classList.add('copied');
    label.textContent = 'Cop';   // the stylesheet appends "ied!"
    setTimeout(() => { button.classList.remove('copied'); label.textContent = 'Copy'; }, 2000);
  };
  const fallback = () => {
    const area = document.createElement('textarea');
    area.value = code.textContent;
    document.body.appendChild(area);
    area.select();
    document.execCommand('copy');
    document.body.removeChild(area);
    done();
  };
  if (navigator.clipboard) navigator.clipboard.writeText(code.textContent).then(done, fallback);
  else fallback();
}

function scrollToTop() {
  window.scrollTo({ top: 0, behavior: 'smooth' });
}

window.addEventListener('scroll', () => {
  const button = document.querySelector('.scroll-to-top');
  if (button) button.classList.toggle('visible', window.pageYOffset > 300);
});

// Figures are inlined so that their text is selectable and the page's light palette applies to them. Each one
// links to its SVG file, which a phone can open full size and zoom.
function inlineFigures() {
  document.querySelectorAll('.ftc-figure img[src$=".svg"]').forEach((img) => {
    const src = img.getAttribute('src');
    fetch(src)
      .then((r) => (r.ok ? r.text() : null))
      .then((svg) => {
        if (!svg || !/^\s*<svg[\s>]/.test(svg)) return;
        const holder = document.createElement('div');
        holder.innerHTML = svg;
        const el = holder.firstElementChild;
        if (!el) return;
        const link = document.createElement('a');
        link.href = src;
        link.target = '_blank';
        link.rel = 'noopener';
        link.className = 'figure-link';
        link.title = 'Open the figure full size';
        link.appendChild(el);
        img.replaceWith(link);
      })
      .catch(() => {});
  });
}

document.addEventListener('DOMContentLoaded', () => {
  if (window.bulmaCarousel) {
    bulmaCarousel.attach('.carousel', {
      slidesToScroll: 1, slidesToShow: 1, loop: true, infinite: true, autoplay: true, autoplaySpeed: 6000,
    });
  }
  inlineFigures();
});
