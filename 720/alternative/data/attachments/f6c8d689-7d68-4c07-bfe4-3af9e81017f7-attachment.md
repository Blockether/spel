## DELETE http://localhost:46051/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Tue, 18 Aug 2026 10:14:45 GMT
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
curl 'http://localhost:46051/echo' \
  -X DELETE
```
