## PUT http://localhost:40741/echo → 200 OK

### Request Headers
```
PUT http://localhost:40741/echo
```

### Request Body
```json
{
  "name": "Eve Updated"
}
```

### Response Headers
```
content-length: 61
content-type: application/json
date: Thu, 09 Apr 2026 19:57:13 GMT
```

### Response Body
```json
{
  "method": "PUT",
  "path": "/echo",
  "body": {
    "name": "Eve Updated"
  }
}
```

### cURL
```bash
curl 'http://localhost:40741/echo' \
  -X PUT \
  -d '{
  "name": "Eve Updated"
}'
```
