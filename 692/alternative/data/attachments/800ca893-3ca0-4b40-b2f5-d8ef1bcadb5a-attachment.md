## DELETE http://localhost:36239/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Sun, 09 Aug 2026 20:00:45 GMT
```

### Response Body
```json
{
  "method": "DELETE",
  "path": "/echo"
}
```

### cURL
```bash
curl 'http://localhost:36239/echo' \
  -X DELETE
```
