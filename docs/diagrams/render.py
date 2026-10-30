"""Original Roster Sync diagrams. Run: python docs/diagrams/render.py.

Requires matplotlib==3.10.7. Geometry and captions describe this repository;
no third-party diagram assets or layouts are imported.
"""
from pathlib import Path
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.patches import FancyBboxPatch, FancyArrowPatch, Rectangle

OUT=Path(__file__).resolve().parent
INK='#1B2430'; MUTED='#5B6673'; RULE='#D5DBD6'; TEAL='#00756F'
PAPER='#F3F5F2'; HR='#0057B8'; PURPLE='#7A3E9D'; AMBER='#F2B705'; RED='#B42318'
plt.rcParams.update({'font.family':'DejaVu Sans','svg.fonttype':'path','svg.hashsalt':'roster-sync-docs-v1','font.size':11})

def canvas(title,subtitle,height=790):
    fig,ax=plt.subplots(figsize=(14,height/100),dpi=150)
    fig.patch.set_facecolor(PAPER);ax.set_facecolor(PAPER)
    ax.set(xlim=(0,1400),ylim=(0,height));ax.axis('off')
    ax.text(35,height-45,title,fontsize=22,fontweight='bold',color=INK,va='center')
    ax.text(35,height-78,subtitle,fontsize=11,color=MUTED,va='center')
    fig.subplots_adjust(left=0,right=1,bottom=0,top=1)
    return fig,ax

def box(ax,x,y,w,h,title,body='',color=TEAL,fill='white',size=11):
    ax.add_patch(FancyBboxPatch((x,y),w,h,boxstyle='round,pad=0,rounding_size=5',facecolor=fill,edgecolor=color,linewidth=1.4))
    ax.text(x+14,y+h-23,title,fontsize=size,fontweight='bold',color=INK,va='center')
    if body: ax.text(x+14,y+h-46,body,fontsize=9.5,color=INK,va='top',linespacing=1.65)

def arrow(ax,points,color=TEAL,dashed=False,label=None,at=None):
    for a,b in zip(points[:-2],points[1:-1]): ax.plot([a[0],b[0]],[a[1],b[1]],color=color,lw=1.5,ls='--' if dashed else '-')
    ax.add_patch(FancyArrowPatch(points[-2],points[-1],arrowstyle='-|>',mutation_scale=12,linewidth=1.5,color=color,linestyle='--' if dashed else '-'))
    if label:
        ax.text(*at,label,fontsize=9,color=color,ha='center',va='center',bbox={'facecolor':PAPER,'edgecolor':'none','pad':2})

def save(fig,name):
    fig.savefig(OUT/(name+'.svg'),metadata={'Date':None})
    fig.savefig(OUT/(name+'.png'),dpi=150)
    plt.close(fig)

def architecture():
    fig,ax=canvas('Two input paths. One authoritative roster.','Java 21 / Spring Boot 4.1     |     one origin, one PostgreSQL store, no message broker')
    ax.add_patch(Rectangle((280,132),740,550,facecolor='none',edgecolor=RULE,linewidth=1.5))
    ax.text(302,660,'Spring Boot application  (React console is served as static files)',fontsize=10,color=MUTED)
    box(ax,30,550,210,85,'HR system','Create / update / deactivate\nVersioned HR state',HR)
    box(ax,30,405,210,85,'Nightly export','Up to 200,000 employees\nTimestamp + optional trailer',HR)
    box(ax,30,230,210,105,'People at the console','Operator: approve / reject\nAdmin: transport fields\nEmployee: self profile',PURPLE)
    box(ax,320,575,660,56,'JWT, role authorization and tenant context',color=TEAL)
    box(ax,320,430,295,105,'Event ingestion','Validate each record independently\nFilter fields by owner\nReserve receipt; lock employee',HR)
    box(ax,680,430,300,105,'Atomic state + event write','Employee + outbox in one transaction\nReceipt for API events only\nIdentical newer state: version only')
    box(ax,320,280,295,105,'Nightly comparison','Stream to 256 on-disk buckets\nRead and compare one bucket at a time\nPersist an actionable plan',HR)
    box(ax,680,280,300,105,'Safety brake','At least 30% missing: stop all apply\nAlert the operator; keep people active\nApprove all / skip exits / reject',fill=AMBER)
    box(ax,320,155,295,80,'Deactivation consumer','Deduplicate; re-check current status\nCancel future bookings and free seats',PURPLE)
    box(ax,680,155,300,80,'Outbox publisher','One claimed row per transaction\nRetry with backoff; alert on DEAD')
    box(ax,1080,430,290,150,'PostgreSQL 17','Employee state and source versions\nAccepted-event receipts\nReconciliation plan and decisions\nOutbox, alerts, bookings and seats')
    box(ax,1080,230,290,100,'Operations','Tenant SQL summary; finite metrics\n8081 management on loopback\nPrometheus / Grafana: local only',color=RULE)
    arrow(ax,[(240,600),(320,600)],HR)
    arrow(ax,[(240,448),(265,448),(265,603),(320,603)],HR)
    arrow(ax,[(240,280),(265,280),(265,590),(320,590)],PURPLE)
    arrow(ax,[(465,575),(465,535)],HR)
    arrow(ax,[(335,575),(302,575),(302,332),(320,332)],HR)
    arrow(ax,[(615,482),(680,482)])
    arrow(ax,[(615,333),(680,333)])
    arrow(ax,[(830,385),(830,430)],label='clear, or approved',at=(850,406))
    arrow(ax,[(980,485),(1080,485)],label='commit',at=(1030,507))
    arrow(ax,[(1225,430),(1225,358),(1001,358),(1001,196),(980,196)],label='claim PENDING',at=(1132,358))
    arrow(ax,[(680,195),(615,195)],label='dispatch',at=(646,211))
    ax.text(320,103,'Plain Java domain: ownership, validation, version decisions, hashing, bucket diff and brake arithmetic.',fontsize=10,color=INK)
    ax.text(35,44,'Delivery is at least once. Consumer deduplication and current-state checks make retries safe.',fontsize=10,color=MUTED)
    save(fig,'architecture')

def recovery():
    fig,ax=canvas('The difficult boundary: committed, delivered, then crashed.','Outbox recovery with an independently committed consumer. The same event ID is delivered again.',height=830)
    xs=[125,405,745,1135]; labels=['HR request','PostgreSQL','Publisher','Consumer']
    for x,label in zip(xs,labels):
        box(ax,x-95,670,190,50,label)
        ax.plot([x,x],[85,665],color=RULE,lw=1.3,ls='--')
    def msg(a,b,y,text,color=TEAL):
        arrow(ax,[(xs[a],y),(xs[b],y)],color)
        ax.text((xs[a]+xs[b])/2,y+13,text,ha='center',fontsize=10,color=INK,bbox={'facecolor':PAPER,'edgecolor':'none','pad':2})
    msg(0,1,635,'Reserve receipt, lock employee, decide')
    box(ax,265,542,460,65,'COMMIT: employee + receipt + outbox','All three writes succeed together; rollback removes all three.',fill='#E6F2EA')
    msg(1,0,510,'200 Applied')
    msg(2,1,472,'Claim pending row (FOR UPDATE SKIP LOCKED)')
    msg(2,3,422,'Deliver event ID')
    box(ax,985,305,345,100,'Consumer commits independently','processed_event + current-state check\n+ cancel future bookings / free seats',PURPLE)
    ax.plot([732,758],[316,342],color=RED,lw=3);ax.plot([732,758],[342,316],color=RED,lw=3)
    ax.text(710,314,'Process dies before marking Published',ha='right',fontsize=10,color=RED)
    ax.text(745,277,'Restart',ha='center',fontsize=11,color=TEAL,fontweight='bold')
    msg(2,1,244,'Rolled-back claim leaves row pending')
    msg(2,3,196,'Redeliver the SAME event ID')
    msg(3,2,149,'Already processed: no repeated business effect',PURPLE)
    msg(2,1,103,'Mark Published and commit')
    ax.text(35,35,'A timeout may finish late; it cannot cancel the sink. Deduplication is required even without a crash.',fontsize=10,color=MUTED)
    save(fig,'outbox-recovery')

if __name__=='__main__':
    architecture();recovery()
