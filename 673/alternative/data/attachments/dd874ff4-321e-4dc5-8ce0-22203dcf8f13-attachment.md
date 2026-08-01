## DELETE http://localhost:36487/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Sat, 01 Aug 2026 11:40:05 GMT
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
curl 'http://localhost:36487/echo' \
  -X DELETE
```
