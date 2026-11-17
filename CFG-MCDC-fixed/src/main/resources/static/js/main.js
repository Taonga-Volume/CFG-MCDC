// main.js — shared across all pages
document.addEventListener('DOMContentLoaded', () => {
  // Animate elements on scroll
  const els = document.querySelectorAll('.anim-fade-up');
  if ('IntersectionObserver' in window) {
    const obs = new IntersectionObserver((entries) => {
      entries.forEach(e => { if (e.isIntersecting) { e.target.style.opacity = '1'; e.target.style.transform = 'translateY(0)'; } });
    }, { threshold: 0.1 });
    els.forEach(el => {
      el.style.opacity = '0';
      el.style.transform = 'translateY(20px)';
      el.style.transition = 'opacity .5s ease, transform .5s ease';
      obs.observe(el);
    });
  }
});
