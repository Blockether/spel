## PATCH http://localhost:41253/echo → 200 OK

### Request Headers
```
PATCH http://localhost:41253/echo
```

### Request Body
```json
{
  "email": "eve@new.com"
}
```

### Response Headers
```
content-length: 64
content-type: application/json
date: Mon, 30 Mar 2026 11:05:58 GMT
```

### Response Body
```json
{
  "method": "PATCH",
  "path": "/echo",
  "body": {
    "email": "eve@new.com"
  }
}
```

### cURL
```bash
curl 'http://localhost:41253/echo' \
  -X PATCH \
  -d '{
  "email": "eve@new.com"
}'
```
