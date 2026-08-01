## DELETE http://localhost:35465/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Sat, 01 Aug 2026 16:26:53 GMT
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
curl 'http://localhost:35465/echo' \
  -X DELETE
```
