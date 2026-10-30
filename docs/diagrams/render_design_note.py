"""Render the original, print-sized two-page design note with ReportLab."""
from pathlib import Path
from reportlab.pdfgen import canvas
from reportlab.lib.pagesizes import A4
from reportlab.lib import colors
from reportlab.platypus import Paragraph, Table, TableStyle
from reportlab.lib.styles import ParagraphStyle
from reportlab.lib.enums import TA_LEFT

ROOT=Path(__file__).resolve().parents[1]
INK=colors.HexColor('#1B2430'); TEAL=colors.HexColor('#00756F'); RULE=colors.HexColor('#D5DBD6')
STYLE=ParagraphStyle('body',fontName='Helvetica',fontSize=9.5,leading=13,textColor=INK,alignment=TA_LEFT)

def para(c,text,x,y,w=523,size=9.5):
    p=Paragraph(text,ParagraphStyle('p',parent=STYLE,fontSize=size,leading=size+3))
    _,h=p.wrap(w,1000);p.drawOn(c,x,y-h);return y-h

def box(c,x,y,w,h,title,body,fill='#FFFFFF'):
    c.setFillColor(colors.HexColor(fill));c.setStrokeColor(TEAL);c.roundRect(x,y,w,h,4,fill=1)
    c.setFillColor(INK);c.setFont('Helvetica-Bold',11);c.drawString(x+12,y+h-21,title)
    para(c,body,x+12,y+h-31,w-24,9.5)

def arrow(c,x1,y1,x2,y2):
    import math
    c.setStrokeColor(TEAL);c.setFillColor(TEAL);c.setLineWidth(1)
    c.line(x1,y1,x2,y2);a=math.atan2(y2-y1,x2-x1)
    p=c.beginPath();p.moveTo(x2,y2)
    for d in [.5,-.5]:p.lineTo(x2-6*math.cos(a+d),y2-6*math.sin(a+d))
    p.close();c.drawPath(p,fill=1,stroke=0)

def footer(c,page):
    c.setStrokeColor(RULE);c.line(36,33,559,33)
    c.setFont('Helvetica',8);c.setFillColor(INK)
    c.drawString(36,20,'Roster Sync | Case Study 5 | Java 21 / Spring Boot / PostgreSQL')
    c.drawRightString(559,20,f'{page} / 2')

def render():
    c=canvas.Canvas(str(ROOT/'DESIGN.pdf'),pagesize=A4,invariant=1)
    c.setTitle('Roster Sync - Two-page design note');c.setAuthor('Roster Sync')
    c.setFillColor(INK);c.setFont('Helvetica-Bold',22);c.drawString(36,805,'Roster Sync: state before delivery')
    para(c,'Idempotent HR events, out-of-order versions and safe nightly reconciliation. One application and one PostgreSQL primary; the React console shares the API origin.',36,785)
    box(c,36,676,523,52,'Authenticated boundary','JWT role check, then tenant-scoped lookup. The server owns all business decisions.')
    box(c,36,535,245,105,'Real-time record','Validate and filter ownership.<br/>Reserve the receipt key.<br/>Lock employee; compare source version.')
    box(c,314,535,245,105,'Nightly snapshot','Stream into 256 on-disk buckets.<br/>Compare one bucket at a time.<br/>Persist plan; brake at &gt;=30% missing.','#FBF1DD')
    arrow(c,160,676,160,640);arrow(c,436,676,436,640)
    box(c,36,425,523,70,'Atomic PostgreSQL commit','Employee state + outbox in one transaction; API events also finalize a receipt.<br/>Newer identical HR state advances the version with NO_CHANGE and no business event.')
    arrow(c,160,535,160,495);arrow(c,436,535,436,495)
    box(c,36,321,245,65,'Publisher','Claim one pending row per transaction.<br/>Timeout, backoff and DEAD alert.')
    box(c,314,321,245,65,'Consumer','Deduplicate event ID; lock employee.<br/>Cancel future bookings; free seats.')
    arrow(c,160,425,160,386);arrow(c,281,354,314,354)
    y=290
    for title,body in [
      ('Identity and ownership','Employee key: tenant + external ID. Receipt key: tenant + employee + source version. CREATE/UPDATE carry full HR state. HR input never overwrites pickup point or special assistance; invalid rows do not consume a key.'),
      ('Bounded work','Comparison: O(N + M) time, expected O((N + M)/256) heap, O(N) spill disk. Skew sets the largest bucket. A brake stops all apply until an operator decides. Apply locks only the relevant employee rows in 40-action chunks and rechecks expected versions.'),
      ('Explainable core','api -> application -> pure Java domain. Infrastructure supplies JPA/JDBC, security, cache and metrics. Domain rules have no Spring, Jakarta or SQL dependencies. Node exists only in the static build stage.')]:
        c.setFont('Helvetica-Bold',10);c.drawString(36,y,title);y=para(c,body,36,y-8)-16
    footer(c,1);c.showPage()
    c.setFillColor(INK);c.setFont('Helvetica-Bold',19);c.drawString(36,805,'Hardest flow: crash after delivery')
    para(c,'The consumer can commit before publication is acknowledged. Retrying execution is necessary; idempotent, state-aware effects make that retry safe.',36,784)
    xs=[70,220,370,520]
    for x,name in zip(xs,['HR request','PostgreSQL','Publisher','Consumer']):
        c.setFont('Helvetica-Bold',9);c.drawCentredString(x,733,name)
        c.setStrokeColor(RULE);c.setDash(3,3);c.line(x,717,x,419);c.setDash()
    def msg(a,b,y,label):
        arrow(c,xs[a],y,xs[b],y);c.setFillColor(INK);c.setFont('Helvetica',8.5);c.drawCentredString((xs[a]+xs[b])/2,y+8,label)
    msg(0,1,700,'Reserve / lock / decide')
    c.setFillColor(colors.HexColor('#E6F2EA'));c.rect(105,654,310,27,fill=1,stroke=0)
    c.setFillColor(INK);c.setFont('Helvetica-Bold',9);c.drawCentredString(260,664,'COMMIT employee + receipt + outbox')
    msg(2,1,630,'Claim pending row')
    msg(2,3,589,'Deliver event ID')
    c.setFillColor(INK);c.setFont('Helvetica',8.5);c.drawRightString(559,570,'Consumer commits dedupe + side effects independently')
    c.setFillColor(colors.HexColor('#B42318'));c.setFont('Helvetica-Bold',9);c.drawCentredString(370,543,'Crash before marking Published')
    msg(2,1,513,'Restart: claim remains pending')
    msg(2,3,478,'Redeliver same event ID')
    msg(3,2,449,'Already processed: skip effect')
    msg(2,1,420,'Mark Published; commit')
    c.setFillColor(INK);c.setFont('Helvetica-Bold',11);c.drawString(36,388,'Failure-mode table')
    data=[['Failure','Visible behavior','Recovery'],
      ['Invalid / replay / stale','Per-record rejection or safe no-op','Correct and resend; receipts/version prevent regression.'],
      ['Truncated file','Missing count, alert, no apply','Operator approves all, skips exits or rejects.'],
      ['Crash before commit','No partial employee/event/receipt','Rollback; replay the same input.'],
      ['Crash after commit / delivery','Pending event; possible redelivery','Restart publisher; consumer dedupe/current-state check.'],
      ['Sink timeout / failure','Backoff, then DEAD + one alert','Manual retry; later pending rows continue.'],
      ['Interrupted recon','Failed comparison or pending actions','Re-upload comparison; restart resumes apply.'],
      ['Database unavailable','Safe dependency error; fail closed','No speculative writes; resume after store recovery.']]
    table=Table([[Paragraph(v,ParagraphStyle('t',parent=STYLE,fontSize=8.5,leading=11)) for v in row] for row in data],colWidths=[116,165,242])
    table.setStyle(TableStyle([('VALIGN',(0,0),(-1,-1),'TOP'),('BACKGROUND',(0,0),(-1,0),colors.HexColor('#EEF0F1')),('LINEBELOW',(0,0),(-1,-1),.4,RULE),('LEFTPADDING',(0,0),(-1,-1),6),('RIGHTPADDING',(0,0),(-1,-1),6),('TOPPADDING',(0,0),(-1,-1),5),('BOTTOMPADDING',(0,0),(-1,-1),5)]))
    _,h=table.wrap(523,1000);table.drawOn(c,36,373-h)
    y=358-h
    y=para(c,'<b>Trade-offs:</b> single instance/primary and local upload storage simplify consistency. Multi-instance operation needs leases and shared storage. Caffeine staleness is bounded (office 10 min; employee 30 s), with after-commit eviction. JWTs are stateless and remain valid until expiry after account disablement.',36,y,523,9)
    para(c,'Full schema, API, algorithm, evidence and decision records: docs/DESIGN.md and its linked documents.',36,y-10,523,8.5)
    footer(c,2);c.save()

if __name__=='__main__': render()
