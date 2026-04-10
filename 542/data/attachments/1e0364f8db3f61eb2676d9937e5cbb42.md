## PATCH http://localhost:40343/echo → 200 OK

### Request Headers
```
PATCH http://localhost:40343/echo
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
date: Fri, 10 Apr 2026 03:53:07 GMT
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
curl 'http://localhost:40343/echo' \
  -X PATCH \
  -d '{
  "email": "eve@new.com"
}'
```
