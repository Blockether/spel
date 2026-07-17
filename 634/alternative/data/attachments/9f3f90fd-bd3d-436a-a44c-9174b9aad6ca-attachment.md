## PUT http://localhost:43813/echo → 200 OK

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
date: Fri, 17 Jul 2026 15:42:46 GMT
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
curl 'http://localhost:43813/echo' \
  -X PUT \
  -d '{
  "name": "Eve Updated"
}'
```
